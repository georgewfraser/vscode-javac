package org.javacs;

import com.google.gson.JsonElement;
import com.sun.source.doctree.DocTree;
import com.sun.source.doctree.ParamTree;
import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Logger;
import javax.lang.model.element.*;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.*;
import javax.tools.*;
import org.javacs.lsp.*;

class CompileBatch implements AutoCloseable {
    static final int MAX_COMPLETION_ITEMS = 50;

    private final JavaCompilerService parent;
    private final ReusableCompiler.Borrow borrow;
    private final Trees trees;
    private final Elements elements;
    private final Types types;
    private final List<CompilationUnitTree> roots;

    CompileBatch(JavaCompilerService parent, Collection<? extends JavaFileObject> files) {
        this.parent = parent;
        this.borrow = batchTask(parent, files);
        this.trees = Trees.instance(borrow.task);
        this.elements = borrow.task.getElements();
        this.types = borrow.task.getTypes();
        this.roots = new ArrayList<CompilationUnitTree>();
        // Compile all roots
        try {
            for (var t : borrow.task.parse()) {
                roots.add(t);
            }
            // The results of borrow.task.analyze() are unreliable when errors are present
            // You can get at `Element` values using `Trees`
            borrow.task.analyze();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * If the compilation failed because javac didn't find some package-private files in source files with different
     * names, list those source files.
     */
    Set<Path> needsAdditionalSources() {
        // Check for "class not found errors" that refer to package private classes
        var addFiles = new HashSet<Path>();
        for (var err : parent.diags) {
            if (!err.getCode().equals("compiler.err.cant.resolve.location")) continue;
            var className = errorText(err);
            var packageName = packageName(err);
            var location = findPackagePrivateClass(packageName, className);
            if (location != NOT_FOUND) {
                addFiles.add(location);
            }
        }
        return addFiles;
    }

    private String errorText(javax.tools.Diagnostic<? extends javax.tools.JavaFileObject> err) {
        var file = Paths.get(err.getSource().toUri());
        var contents = FileStore.contents(file);
        var begin = (int) err.getStartPosition();
        var end = (int) err.getEndPosition();
        return contents.substring(begin, end);
    }

    private String packageName(javax.tools.Diagnostic<? extends javax.tools.JavaFileObject> err) {
        var file = Paths.get(err.getSource().toUri());
        return FileStore.packageName(file);
    }

    private static final Path NOT_FOUND = Paths.get("");

    private Path findPackagePrivateClass(String packageName, String className) {
        for (var file : FileStore.list(packageName)) {
            var parse = Parser.parseFile(file);
            for (var candidate : parse.packagePrivateClasses()) {
                if (candidate.contentEquals(className)) {
                    return file;
                }
            }
        }
        return NOT_FOUND;
    }

    @Override
    public void close() {
        borrow.close();
    }

    private static ReusableCompiler.Borrow batchTask(
            JavaCompilerService parent, Collection<? extends JavaFileObject> sources) {
        parent.diags.clear();
        var options = JavaCompilerService.options(parent.classPath, parent.addExports);
        return parent.compiler.getTask(null, parent.fileManager, parent.diags::add, options, List.of(), sources);
    }

    CompilationUnitTree root(Path file) {
        for (var root : roots) {
            if (root.getSourceFile().toUri().equals(file.toUri())) {
                return root;
            }
        }
        // Somehow, file was not in batch
        var names = new StringJoiner(", ");
        for (var r : roots) {
            names.add(StringSearch.fileName(r.getSourceFile().toUri()));
        }
        throw new RuntimeException("File " + file + " isn't in batch " + names);
    }

    private String contents(CompilationUnitTree root) {
        try {
            return root.getSourceFile().getCharContent(true).toString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    Optional<Element> element(Path file, int line, int character) {
        var root = root(file);
        var cursor = root.getLineMap().getPosition(line, character);
        var path = findPath(file, cursor);
        var el = trees.getElement(path);
        return Optional.ofNullable(el);
    }

    private boolean okUnused(Name name) {
        return name.charAt(0) == '_'; // TODO quick fix
    }

    Map<Path, List<DiagnosticHolder>> reportErrors() {
        // Construct empty lists
        var byPath = new HashMap<Path, List<DiagnosticHolder>>();
        for (var r : roots) {
            var path = Paths.get(r.getSourceFile().toUri());
            byPath.put(path, new ArrayList<>());
        }
        // Convert diags
        for (var d : parent.diags) {
            var source = d.getSource();
            if (source == null) continue;
            var path = Paths.get(source.toUri());
            if (!byPath.containsKey(path)) continue;
            byPath.get(path).add(DiagnosticHolder.wrap(d));
        }
        // Check for unused privates
        for (var r : roots) {
            var path = Paths.get(r.getSourceFile().toUri());
            var warnUnused = new WarnUnused(borrow.task);
            warnUnused.scan(r, null);
            for (var unusedEl : warnUnused.notUsed()) {
                if (okUnused(unusedEl.getSimpleName())) continue;
                var warn = warnUnused(unusedEl);
                byPath.get(path).add(warn);
            }
        }
        // TODO hint fields that could be final
        // TODO hint unused exception

        return byPath;
    }

    private DiagnosticHolder warnUnused(Element unusedEl) {
        var path = trees.getPath(unusedEl);
        var root = path.getCompilationUnit();
        var leaf = path.getLeaf();
        var pos = trees.getSourcePositions();
        var start = (int) pos.getStartPosition(root, leaf);
        var end = (int) pos.getEndPosition(root, leaf);
        if (leaf instanceof VariableTree) {
            var file = Paths.get(root.getSourceFile().toUri());
            var contents = FileStore.contents(file);
            var v = (VariableTree) leaf;
            var name = v.getName().toString();
            var offset = (int) pos.getEndPosition(root, v.getType());
            if (offset == -1) offset = start;
            offset = contents.indexOf(name, offset);
            end = offset + name.length();
        }
        var message = String.format("`%s` is not used", unusedEl.getSimpleName());
        int severity;
        if (unusedEl instanceof ExecutableElement || unusedEl instanceof TypeElement) {
            severity = DiagnosticSeverity.Hint;
        } else {
            severity = DiagnosticSeverity.Information;
        }
        return DiagnosticHolder.warnUnused(severity, message, start, end);
    }

    Optional<List<TreePath>> definitions(Element el) {
        LOG.info(String.format("Search for definitions of `%s` in %d files...", el, roots.size()));
        if (el.asType().getKind() == TypeKind.ERROR) {
            LOG.info(String.format("...`%s` is an error type, giving up", el.asType()));
            return Optional.empty();
        }
        var finder = new FindDefinitions(el, borrow.task);
        for (var r : roots) {
            finder.scan(r, null);
        }
        return Optional.of(finder.results);
    }

    Optional<List<TreePath>> references(Path toFile, int toLine, int toColumn) {
        var to = element(toFile, toLine, toColumn);
        if (to.isEmpty()) {
            LOG.info(String.format("...no element at %s(%d, %d), giving up", toFile, toLine, toColumn));
            return Optional.empty();
        }
        // If to is an error, we won't be able to find anything
        if (to.get().asType().getKind() == TypeKind.ERROR) {
            LOG.info(String.format("...`%s` is an error type, giving up", to.get().asType()));
            return Optional.empty();
        }
        // Otherwise, scan roots for references
        List<TreePath> list = new ArrayList<TreePath>();
        var map = Map.of(to.get(), list);
        var finder = new FindReferences(borrow.task);
        for (var r : roots) {
            // TODO jump to scan takes me to a specific method in this file, which is misleading. The actual
            // implementation is in the super of FindReferences.
            finder.scan(r, map);
        }
        return Optional.of(list);
    }

    Optional<Range> range(TreePath path) {
        var file = Paths.get(path.getCompilationUnit().getSourceFile().toUri());
        var contents = FileStore.contents(file);
        return Parser.range(borrow.task, contents, path);
    }

    SourcePositions sourcePositions() {
        return trees.getSourcePositions();
    }

    LineMap lineMap(Path file) {
        return root(file).getLineMap();
    }

    List<? extends ImportTree> imports(Path file) {
        return root(file).getImports();
    }

    /** Find methods that override a method from a superclass but don't have an @Override annotation. */
    List<TreePath> needsOverrideAnnotation(Path file) {
        LOG.info(String.format("Looking for methods that need an @Override annotation in %s ...", file));
        var root = root(file);
        var finder = new FindMissingOverride(borrow.task);
        finder.scan(root, null);
        return finder.results;
    }

    /**
     * Figure out what imports this file should have. Star-imports like `import java.util.*` are converted to individual
     * class imports. Missing imports are inferred by looking at imports in other source files.
     */
    List<String> fixImports(Path file) {
        var root = root(file);
        var contents = contents(root);
        // Check diagnostics for missing imports
        var unresolved = new HashSet<String>();
        for (var d : parent.diags) {
            if (d.getCode().equals("compiler.err.cant.resolve.location")
                    && d.getSource().toUri().equals(file.toUri())) {
                long start = d.getStartPosition(), end = d.getEndPosition();
                var id = contents.substring((int) start, (int) end);
                if (id.matches("[A-Z]\\w+")) {
                    unresolved.add(id);
                } else LOG.warning(id + " doesn't look like a class");
            }
        }
        // Look at imports in other classes to help us guess how to fix imports
        // TODO cache parsed imports on a per-file basis
        var sourcePathImports = StringSearch.existingImports(FileStore.all());
        var classes = new HashSet<String>();
        classes.addAll(parent.jdkClasses);
        classes.addAll(parent.classPathClasses);
        var fixes = StringSearch.resolveSymbols(unresolved, sourcePathImports, classes);
        // Figure out which existing imports are actually used
        var finder = new FindUsedImports(borrow.task, root);
        finder.scan(root, null);
        // If `file` contains errors, don't try to fix imports, it's too inaccurate
        var hasErrors = hasErrors(file);
        // Take the intersection of existing imports ^ existing identifiers

        var javaImports = new TreeSet<String>();
        var javaxImports = new TreeSet<String>();
        var comImports = new TreeSet<String>();
        var orgImports = new TreeSet<String>();
        var ioImports = new TreeSet<String>();
        var customImports = new TreeSet<String>();

        for (var i : root.getImports()) {
            var imported = i.getQualifiedIdentifier().toString();
            var isUsed = false;
            if (imported.endsWith(".*")) {
                var packageName = StringSearch.mostName(imported);
                if (hasErrors || finder.references.stream().anyMatch(r -> r.startsWith(packageName))) isUsed = true;
                else LOG.warning("There are no references to package " + imported);
            } else {
                if (hasErrors || finder.references.contains(imported)) isUsed = true;
                else LOG.warning("There are no references to class " + imported);
            }

            if (isUsed) {
              switch (getPackageComponent(imported, 0)) {
                case "java":
                  javaImports.add(imported);
                  break;

                case "javax":
                  javaxImports.add(imported);
                  break;

                case "com":
                  comImports.add(imported);
                  break;

                case "org":
                  orgImports.add(imported);
                  break;

                case "io":
                  ioImports.add(imported);
                  break;

                default:
                  customImports.add(imported);
                  break;
              }
            }
        }

        var imports = new ArrayList<String>();
        addImportGroupToList(imports, javaImports, false);
        addImportGroupToList(imports, javaxImports, false);
        addImportGroupToList(imports, comImports, true);
        addImportGroupToList(imports, ioImports, true);
        addImportGroupToList(imports, orgImports, true);
        addImportGroupToList(imports, customImports, true);
        return imports;
    }

    private void addImportGroupToList(List<String> allImports, SortedSet<String> group, boolean splitFurther) {
        if (group.isEmpty()) return;

        if (!allImports.isEmpty()) allImports.add(null);
        String lastAdded = null;
        for (var i : group) {
            if (splitFurther && lastAdded != null) {
                if (!safeEquals(getPackageComponent(i, 1), getPackageComponent(lastAdded, 1))) {
                    allImports.add(null);
                }
            }
            allImports.add(i);
            lastAdded = i;
        }
    }

    private String getPackageComponent(String qualifiedName, int index) {
        var split = qualifiedName.split("\\.");
        return (index < split.length) ? split[index] : null;
    }

    private boolean safeEquals(String s1, String s2) {
        if (s1 == null && s2 == null) return true;
        if (s1 == null || s2 == null) return false;
        return s1.equals(s2);
    }

    private boolean hasErrors(Path file) {
        for (var d : parent.diags) {
            if (d.getKind() != javax.tools.Diagnostic.Kind.ERROR) continue;
            if (!d.getSource().toUri().equals(file.toUri())) continue;
            if (d.getCode().equals("compiler.err.cant.resolve.location")) continue;
            return true;
        }
        return false;
    }

    /** Find all overloads for the smallest method call that includes the cursor */
    Optional<SignatureHelp> signatureHelp(Path file, int cursor) {
        var path = findPath(file, cursor);
        var invokePath = surroundingInvocation(path);
        if (invokePath == null) {
            return Optional.empty();
        }
        if (invokePath.getLeaf() instanceof MethodInvocationTree) {
            var invokeLeaf = (MethodInvocationTree) invokePath.getLeaf();
            var overloads = methodOverloads(invokePath);
            // Figure out which parameter is active
            var activeParameter = invokeLeaf.getArguments().indexOf(path.getLeaf());
            if (activeParameter == -1) activeParameter = 0;
            LOG.info(String.format("...active parameter `%s` is %d", path.getLeaf(), activeParameter));
            // Figure out which method is active, if possible
            var methodSelectPath = trees.getPath(invokePath.getCompilationUnit(), invokeLeaf.getMethodSelect());
            var methodEl = trees.getElement(methodSelectPath);
            ExecutableElement activeMethod = null;
            if (methodEl instanceof ExecutableElement) {
                activeMethod = (ExecutableElement) methodEl;
            }
            return Optional.of(asSignatureHelp(activeMethod, activeParameter, overloads));
        }
        if (invokePath.getLeaf() instanceof NewClassTree) {
            var invokeLeaf = (NewClassTree) invokePath.getLeaf();
            var overloads = constructorOverloads(invokePath);
            // Figure out which parameter is active
            var activeParameter = invokeLeaf.getArguments().indexOf(path.getLeaf());
            if (activeParameter == -1) activeParameter = 0;
            LOG.info(String.format("...active parameter `%s` is %d", path.getLeaf(), activeParameter));
            // Figure out which method is active, if possible
            var methodEl = trees.getElement(invokePath);
            ExecutableElement activeMethod = null;
            if (methodEl instanceof ExecutableElement) {
                activeMethod = (ExecutableElement) methodEl;
            }
            return Optional.of(asSignatureHelp(activeMethod, activeParameter, overloads));
        }
        return Optional.empty();
    }

    private TreePath surroundingInvocation(TreePath cursor) {
        for (var path = cursor; path != null; path = path.getParentPath()) {
            if (path.getLeaf() instanceof MethodInvocationTree || path.getLeaf() instanceof NewClassTree) {
                return path;
            }
        }
        return null;
    }

    private List<ExecutableElement> methodOverloads(TreePath path) {
        // Find all overloads of method
        LOG.info(String.format("...`%s` is a method invocation", path.getLeaf()));
        var invoke = (MethodInvocationTree) path.getLeaf();
        var method = invoke.getMethodSelect();
        var scope = trees.getScope(path);
        if (method instanceof IdentifierTree) {
            var id = (IdentifierTree) method;
            return scopeOverloads(path.getCompilationUnit(), scope, id.getName());
        } else if (method instanceof MemberSelectTree) {
            var select = (MemberSelectTree) method;
            var containerPath = trees.getPath(path.getCompilationUnit(), select.getExpression());
            var containerEl = trees.getElement(containerPath);
            if (containerEl instanceof TypeElement) {
                return typeMemberOverloads(scope, (TypeElement) containerEl, select.getIdentifier());
            } else {
                var type = trees.getTypeMirror(containerPath);
                return instanceMemberOverloads(scope, type, select.getIdentifier());
            }
        } else {
            return List.of();
        }
    }

    private List<ExecutableElement> constructorOverloads(TreePath path) {
        // Find all overloads of method
        LOG.info(String.format("...`%s` is a constructor invocation", path.getLeaf()));
        var method = trees.getElement(path);
        var results = new ArrayList<ExecutableElement>();
        for (var m : method.getEnclosingElement().getEnclosedElements()) {
            if (m.getKind() == ElementKind.CONSTRUCTOR) {
                results.add((ExecutableElement) m);
            }
        }
        return results;
    }

    private List<ExecutableElement> scopeOverloads(CompilationUnitTree root, Scope scope, Name name) {
        var ids = identifiers(root, scope, candidate -> candidate.equals(name));
        var methods = new ArrayList<ExecutableElement>();
        for (var method : ids) {
            if (method instanceof ExecutableElement) {
                methods.add((ExecutableElement) method);
            }
        }
        return methods;
    }

    private List<ExecutableElement> typeMemberOverloads(Scope scope, TypeElement container, Name name) {
        var members = typeMembers(scope, container);
        var methods = new ArrayList<ExecutableElement>();
        for (var member : members) {
            if (member instanceof ExecutableElement && member.getSimpleName().equals(name)) {
                methods.add((ExecutableElement) member);
            }
        }
        return methods;
    }

    private List<ExecutableElement> instanceMemberOverloads(Scope scope, TypeMirror container, Name name) {
        var members = instanceMembers(scope, container);
        var methods = new ArrayList<ExecutableElement>();
        for (var member : members) {
            if (member instanceof ExecutableElement && member.getSimpleName().equals(name)) {
                methods.add((ExecutableElement) member);
            }
        }
        return methods;
    }

    private SignatureHelp asSignatureHelp(
            ExecutableElement activeMethod, int activeParameter, List<ExecutableElement> overloads) {
        var sigs = new ArrayList<SignatureInformation>();
        for (var e : overloads) {
            sigs.add(asSignatureInformation(e));
        }
        int activeSig = 0;
        if (activeMethod != null) {
            activeSig = overloads.indexOf(activeMethod);
        }
        return new SignatureHelp(sigs, activeSig, activeParameter);
    }

    private SignatureInformation asSignatureInformation(ExecutableElement e) {
        var i = new SignatureInformation();
        // Get docs from source if possible, ExecutableElement if not
        if (!addSignatureDocs(e, i)) {
            i.parameters = signatureParamsFromMethod(e);
        }
        // Compute label from params (which came from either source or ExecutableElement)
        var name = e.getSimpleName();
        if (name.contentEquals("<init>")) name = e.getEnclosingElement().getSimpleName();
        var args = new StringJoiner(", ");
        for (var p : i.parameters) {
            args.add(p.label);
        }
        i.label = name + "(" + args + ")";

        return i;
    }

    private boolean addSignatureDocs(ExecutableElement e, SignatureInformation sig) {
        // Find the file that contains e
        var ptr = new Ptr(e);
        var file = parent.docs().find(ptr);
        if (!file.isPresent()) return false;
        var parse = Parser.parseJavaFileObject(file.get());
        // Find the tree
        var path = parse.fuzzyFind(ptr);
        if (!path.isPresent()) return false;
        if (!(path.get().getLeaf() instanceof MethodTree)) return false;
        var method = (MethodTree) path.get().getLeaf();
        // Find the docstring on method, or empty doc if there is none
        var doc = parse.doc(path.get());
        sig.documentation = Parser.asMarkupContent(doc);
        // Get param docs from @param tags
        var paramComments = new HashMap<String, String>();
        for (var tag : doc.getBlockTags()) {
            if (tag.getKind() == DocTree.Kind.PARAM) {
                var param = (ParamTree) tag;
                paramComments.put(param.getName().toString(), Parser.asMarkdown(param.getDescription()));
            }
        }
        // Get param names from source
        sig.parameters = new ArrayList<ParameterInformation>();
        for (var i = 0; i < e.getParameters().size(); i++) {
            var fromSource = method.getParameters().get(i);
            var fromType = e.getParameters().get(i);
            var info = new ParameterInformation();
            var name = fromSource.getName().toString();
            var type = ShortTypePrinter.DEFAULT.print(fromType.asType());
            info.label = type + " " + name;
            if (paramComments.containsKey(name)) {
                var markdown = paramComments.get(name);
                info.documentation = new MarkupContent("markdown", markdown);
            }
            sig.parameters.add(info);
        }
        return true;
    }

    private List<ParameterInformation> signatureParamsFromMethod(ExecutableElement e) {
        var missingParamNames = ShortTypePrinter.missingParamNames(e);
        var ps = new ArrayList<ParameterInformation>();
        for (var v : e.getParameters()) {
            var p = new ParameterInformation();
            if (missingParamNames) p.label = ShortTypePrinter.DEFAULT.print(v.asType());
            else p.label = v.getSimpleName().toString();
            ps.add(p);
        }
        return ps;
    }

    List<CompletionItem> completeIdentifiers(
            Path file,
            int cursor,
            boolean insideClass,
            boolean insideMethod,
            String partialName,
            boolean addParens,
            boolean addSemi) {
        LOG.info(String.format("Completing identifiers starting with `%s`...", partialName));
        var root = root(file);
        var result = new ArrayList<CompletionItem>();
        // Add snippets
        if (!insideClass) {
            // If no package declaration is present, suggest package [inferred name];
            if (root.getPackage() == null) {
                var name = FileStore.suggestedPackageName(file);
                result.add(snippetCompletion("package " + name, "package " + name + ";\n\n"));
            }
            // If no class declaration is present, suggest class [file name]
            var hasClassDeclaration = false;
            for (var t : root.getTypeDecls()) {
                if (!(t instanceof ErroneousTree)) {
                    hasClassDeclaration = true;
                }
            }
            if (!hasClassDeclaration) {
                var name = file.getFileName().toString();
                name = name.substring(0, name.length() - ".java".length());
                result.add(snippetCompletion("class " + name, "class " + name + " {\n    $0\n}"));
            }
        }
        // Add identifiers
        var inScope = completeScopeIdentifiers(file, cursor, partialName, addParens, addSemi);
        result.addAll(inScope);
        // Add keywords
        if (!insideClass) {
            addKeywords(TOP_LEVEL_KEYWORDS, partialName, result);
        } else if (!insideMethod) {
            addKeywords(CLASS_BODY_KEYWORDS, partialName, result);
        } else {
            addKeywords(METHOD_BODY_KEYWORDS, partialName, result);
        }

        return result;
    }

    List<CompletionItem> completeAnnotations(Path file, int cursor, String partialName) {
        var result = new ArrayList<CompletionItem>();
        // Add @Override ... snippet
        if ("Override".startsWith(partialName)) {
            // TODO filter out already-implemented methods using thisMethods
            var alreadyShown = new HashSet<String>();
            for (var method : superMethods(file, cursor)) {
                var mods = method.getModifiers();
                if (mods.contains(Modifier.STATIC) || mods.contains(Modifier.PRIVATE)) continue;

                var label = "@Override " + ShortTypePrinter.DEFAULT.printMethod(method);
                var snippet = "Override\n" + new TemplatePrinter().printMethod(method) + " {\n    $0\n}";
                var override = snippetCompletion(label, snippet);
                if (!alreadyShown.contains(label)) {
                    result.add(override);
                    alreadyShown.add(label);
                }
            }
        }
        // Add @Override, @Test, other simple class names
        var inScope = completeScopeIdentifiers(file, cursor, partialName, false, false);
        result.addAll(inScope);
        return result;
    }

    /** Find all case options in the switch expression surrounding line:character */
    List<CompletionItem> completeCases(Path file, int cursor) {
        var path = findPath(file, cursor);
        // Find surrounding switch
        while (!(path.getLeaf() instanceof SwitchTree)) {
            path = path.getParentPath();
        }
        var leaf = (SwitchTree) path.getLeaf();
        path = new TreePath(path, leaf.getExpression());
        LOG.info(String.format("...found switch expression `%s`", leaf.getExpression()));
        // Get members of switched type
        var type = trees.getTypeMirror(path);
        if (type == null) {
            LOG.info("...no type at " + leaf.getExpression());
            return List.of();
        }
        LOG.info(String.format("...switched expression has type `%s`", type));
        var types = borrow.task.getTypes();
        var definition = types.asElement(type);
        if (definition == null) {
            LOG.info("...type has no definition, completing identifiers instead");
            return completeIdentifiers(file, cursor, true, true, "", false, false); // TODO pass partial name
        }
        LOG.info(String.format("...switched expression has definition `%s`", definition));
        var result = new ArrayList<CompletionItem>();
        for (var member : definition.getEnclosedElements()) {
            if (member.getKind() == ElementKind.ENUM_CONSTANT) {
                result.add(caseCompletion(member));
            }
        }

        return result;
    }

    /** Find all members of expression ending at cursor */
    List<CompletionItem> completeMembers(Path file, int cursor, boolean addParens, boolean addSemi) {
        var path = findPath(file, cursor);
        var scope = trees.getScope(path);
        var element = trees.getElement(path);
        var type = trees.getTypeMirror(path);

        if (element instanceof PackageElement) {
            return completePackageMembers(path);
        } else if (element instanceof TypeElement) {
            var members = typeMembers(scope, (TypeElement) element);
            var result = groupByOverload(members, addParens, addSemi, type);
            result.add(keywordCompletion("class"));
            if (isEnclosingClass(type, scope)) {
                result.add(keywordCompletion("this"));
                result.add(keywordCompletion("super"));
            }
            return result;
        } else if (type != null) {
            var members = instanceMembers(scope, type);
            var result = groupByOverload(members, addParens, addSemi, type);
            if (type instanceof ArrayType) {
                result.add(keywordCompletion("length"));
            }
            return result;
        } else {
            LOG.warning(String.format("`...%s` has no type", path.getLeaf()));
            return List.of();
        }
    }

    private boolean isEnclosingClass(TypeMirror type, Scope start) {
        for (var s : fastScopes(start)) {
            // If we reach a static method, stop looking
            var method = s.getEnclosingMethod();
            if (method != null && method.getModifiers().contains(Modifier.STATIC)) {
                return false;
            }
            // If we find the enclosing class
            var thisElement = s.getEnclosingClass();
            if (thisElement != null && thisElement.asType().equals(type)) {
                return true;
            }
            // If the enclosing class is static, stop looking
            if (thisElement != null && thisElement.getModifiers().contains(Modifier.STATIC)) {
                return false;
            }
        }
        return false;
    }

    List<CompletionItem> completeReferences(Path file, int cursor) {
        var path = findPath(file, cursor);
        var scope = trees.getScope(path);
        var element = trees.getElement(path);
        var type = trees.getTypeMirror(path);
        if (element instanceof TypeElement) {
            var t = (TypeElement) element;
            LOG.info(String.format("...completing static methods of %s", t.getQualifiedName()));
            // Add members
            var methods = new ArrayList<Element>();
            for (var member : t.getEnclosedElements()) {
                if (member.getKind() == ElementKind.METHOD
                        && trees.isAccessible(scope, member, (DeclaredType) t.asType())) {
                    methods.add(member);
                }
            }
            // Form result
            var result = groupByOverload(methods, false, false, t.asType());
            // Add ::new
            result.add(keywordCompletion("new"));

            return result;
        } else {
            var members = instanceMembers(scope, type);
            var result = groupByOverload(members, false, false, element.asType());
            return result;
        }
    }

    private List<CompletionItem> completePackageMembers(TreePath path) {
        var result = new ArrayList<CompletionItem>();
        var scope = trees.getScope(path);
        var element = (PackageElement) trees.getElement(path);

        LOG.info(String.format("...completing members of package %s", element.getQualifiedName()));

        // Add class-names resolved as Element by javac
        for (var member : element.getEnclosedElements()) {
            // If the package member is a TypeElement, like a class or interface, check if it's accessible
            if (member instanceof TypeElement) {
                if (trees.isAccessible(scope, (TypeElement) member)) {
                    result.add(packageCompletion(member));
                }
            }
            // Otherwise, just assume it's accessible and add it to the list
            else {
                result.add(packageCompletion(member));
            }
        }
        // Add sub-package names resolved as String by guava ClassPath
        var parent = element.getQualifiedName().toString();
        var subs = subPackages(parent);
        for (var sub : subs) {
            result.add(packageCompletion(sub, StringSearch.lastName(sub)));
        }

        return result;
    }

    private List<Element> typeMembers(Scope scope, TypeElement element) {
        LOG.info(String.format("...completing static members of %s", element.getQualifiedName()));
        var result = new ArrayList<Element>();
        for (var member : element.getEnclosedElements()) {
            if (member.getModifiers().contains(Modifier.STATIC)
                    && trees.isAccessible(scope, member, (DeclaredType) element.asType())) {
                result.add(member);
            }
        }
        return result;
    }

    private List<Element> instanceMembers(Scope scope, TypeMirror type) {
        if (!hasMembers(type)) {
            LOG.warning("...don't know how to complete members of type " + type);
            return List.of();
        }
        var result = new ArrayList<Element>();
        // TODO consider replacing this with elements.getAllMembers(type)
        var ts = supersWithSelf(type);
        var alreadyAdded = new HashSet<String>();
        LOG.info(String.format("...completing virtual members of %s and %d supers", type, ts.size()));
        for (var t : ts) {
            var e = types.asElement(t);
            if (e == null) {
                LOG.warning(String.format("...can't convert supertype `%s` to element, skipping", t));
                continue;
            }
            for (var member : e.getEnclosedElements()) {
                // Don't add statics
                if (member.getModifiers().contains(Modifier.STATIC)) continue;
                // Don't add constructors
                if (member.getSimpleName().contentEquals("<init>")) continue;
                // Skip overridden members from superclass
                if (alreadyAdded.contains(member.toString())) continue;

                // If type is a DeclaredType, check accessibility of member
                if (type instanceof DeclaredType) {
                    if (trees.isAccessible(scope, member, (DeclaredType) type)) {
                        result.add(member);
                        alreadyAdded.add(member.toString());
                    }
                }
                // Otherwise, accessibility rules are very complicated
                // Give up and just declare that everything is accessible
                else {
                    result.add(member);
                    alreadyAdded.add(member.toString());
                }
            }
        }
        return result;
    }

    private List<CompletionItem> groupByOverload(
            List<Element> members, boolean addParens, boolean addSemi, TypeMirror container) {
        var result = new ArrayList<CompletionItem>();
        var methods = new HashMap<Name, List<ExecutableElement>>();
        for (var member : members) {
            if (member instanceof ExecutableElement) {
                var method = (ExecutableElement) member;
                var name = method.getSimpleName();
                if (!methods.containsKey(name)) {
                    methods.put(name, new ArrayList<ExecutableElement>());
                }
                methods.get(name).add(method);
            } else if (member instanceof VariableElement) {
                result.add(varCompletion((VariableElement) member, container));
            } else if (member instanceof TypeElement) {
                result.add(innerTypeCompletion((TypeElement) member, container));
            }
        }
        for (var overload : methods.values()) {
            var i = methodCompletion(overload, addParens, addSemi, container);
            result.add(i);
        }
        return result;
    }

    static String[] TOP_LEVEL_KEYWORDS = {
        "package",
        "import",
        "public",
        "private",
        "protected",
        "abstract",
        "class",
        "interface",
        "extends",
        "implements",
    };

    private static String[] CLASS_BODY_KEYWORDS = {
        "public",
        "private",
        "protected",
        "static",
        "final",
        "native",
        "synchronized",
        "abstract",
        "default",
        "class",
        "interface",
        "void",
        "boolean",
        "int",
        "long",
        "float",
        "double",
    };

    private static String[] METHOD_BODY_KEYWORDS = {
        "new",
        "assert",
        "try",
        "catch",
        "finally",
        "throw",
        "return",
        "break",
        "case",
        "continue",
        "default",
        "do",
        "while",
        "for",
        "switch",
        "if",
        "else",
        "instanceof",
        "var",
        "final",
        "class",
        "void",
        "boolean",
        "int",
        "long",
        "float",
        "double",
    };

    private List<ExecutableElement> virtualMethods(DeclaredType type) {
        var result = new ArrayList<ExecutableElement>();
        for (var member : type.asElement().getEnclosedElements()) {
            if (member instanceof ExecutableElement) {
                var method = (ExecutableElement) member;
                if (!method.getSimpleName().contentEquals("<init>")
                        && !method.getModifiers().contains(Modifier.STATIC)) {
                    result.add(method);
                }
            }
        }
        return result;
    }

    private TypeMirror enclosingClass(Path file, int cursor) {
        var path = findPath(file, cursor);
        while (!(path.getLeaf() instanceof ClassTree)) {
            path = path.getParentPath();
        }
        return trees.getElement(path).asType();
    }

    private void collectSuperMethods(TypeMirror thisType, List<ExecutableElement> result) {
        var types = borrow.task.getTypes();

        for (var superType : types.directSupertypes(thisType)) {
            if (superType instanceof DeclaredType) {
                var type = (DeclaredType) superType;
                result.addAll(virtualMethods(type));
                collectSuperMethods(type, result);
            }
        }
    }

    private List<ExecutableElement> superMethods(Path file, int cursor) {
        var thisType = enclosingClass(file, cursor);
        var result = new ArrayList<ExecutableElement>();

        collectSuperMethods(thisType, result);

        return result;
    }

    private boolean isImported(Path file, String qualifiedName) {
        var root = root(file);
        var packageName = StringSearch.mostName(qualifiedName);
        var className = StringSearch.lastName(qualifiedName);
        for (var i : root.getImports()) {
            var importName = i.getQualifiedIdentifier().toString();
            var importPackage = StringSearch.mostName(importName);
            var importClass = StringSearch.lastName(importName);
            if (importClass.equals("*") && importPackage.equals(packageName)) return true;
            if (importClass.equals(className) && importPackage.equals(packageName)) return true;
        }
        return false;
    }

    private Set<TypeMirror> supersWithSelf(TypeMirror t) {
        var types = new HashSet<TypeMirror>();
        collectSupers(t, types);
        // Object type is not included by default
        // We need to add it to get members like .equals(other) and .hashCode()
        types.add(borrow.task.getElements().getTypeElement("java.lang.Object").asType());
        return types;
    }

    private void collectSupers(TypeMirror t, Set<TypeMirror> supers) {
        supers.add(t);
        for (var s : types.directSupertypes(t)) {
            collectSupers(s, supers);
        }
    }

    private boolean hasMembers(TypeMirror type) {
        switch (type.getKind()) {
            case ARRAY:
            case DECLARED:
            case ERROR:
            case TYPEVAR:
            case WILDCARD:
            case UNION:
            case INTERSECTION:
                return true;
            case BOOLEAN:
            case BYTE:
            case SHORT:
            case INT:
            case LONG:
            case CHAR:
            case FLOAT:
            case DOUBLE:
            case VOID:
            case NONE:
            case NULL:
            case PACKAGE:
            case EXECUTABLE:
            case OTHER:
            default:
                return false;
        }
    }

    private List<Element> identifiers(CompilationUnitTree root, Scope scope, Predicate<CharSequence> test) {
        var locals = scopeMembers(scope, test);
        LOG.info(String.format("...found %d locals", locals.size()));
        var statics = staticImports(root, test);
        LOG.info(String.format("...found %d static imports", statics.size()));
        var both = new ArrayList<Element>();
        both.addAll(statics);
        both.addAll(locals);
        return both;
    }

    /** Find all identifiers in scope at line:character */
    private List<Element> scopeMembers(Scope start, Predicate<CharSequence> test) {
        var isStatic = false;
        var results = new ArrayList<Element>();
        for (var s : fastScopes(start)) {
            if (s.getEnclosingMethod() != null) {
                isStatic = isStatic || s.getEnclosingMethod().getModifiers().contains(Modifier.STATIC);
            }
            for (var e : s.getLocalElements()) {
                var name = e.getSimpleName();
                if (!test.test(name)) continue;
                if (isStatic && name.contentEquals("this")) continue;
                if (isStatic && name.contentEquals("super")) continue;
                results.add(e);
            }
            if (s.getEnclosingClass() != null) {
                var c = s.getEnclosingClass();
                var t = (DeclaredType) c.asType();
                if (!trees.isAccessible(start, c)) continue;
                var members = elements.getAllMembers(c);
                for (var e : members) {
                    if (!test.test(e.getSimpleName())) continue;
                    if (!trees.isAccessible(start, e, t)) continue;
                    if (isStatic && !e.getModifiers().contains(Modifier.STATIC)) continue;
                    results.add(e);
                }
                isStatic = isStatic || c.getModifiers().contains(Modifier.STATIC);
            }
            // Return early?
            if (tooManyItems(results)) {
                return results;
            }
        }
        return results;
    }

    private List<Scope> fastScopes(Scope start) {
        var scopes = new ArrayList<Scope>();
        for (var s = start; s != null; s = s.getEnclosingScope()) {
            scopes.add(s);
        }
        // Scopes may be contained in an enclosing scope.
        // The outermost scope contains those elements available via "star import" declarations;
        // the scope within that contains the top level elements of the compilation unit, including any named
        // imports.
        // https://parent.docs.oracle.com/en/java/javase/11/docs/api/jdk.compiler/com/sun/source/tree/Scope.html
        return scopes.subList(0, scopes.size() - 2);
    }

    private boolean tooManyItems(List<Element> elements) {
        var distinctNames = new HashSet<Name>();
        for (var e : elements) {
            distinctNames.add(e.getSimpleName());
        }
        if (distinctNames.size() >= MAX_COMPLETION_ITEMS) {
            LOG.warning(String.format("...# of items %d reached max %s", distinctNames.size(), MAX_COMPLETION_ITEMS));
            return true;
        }
        return false;
    }

    private boolean tooManyItems(int count) {
        if (count >= MAX_COMPLETION_ITEMS) {
            LOG.warning(String.format("...# of items %d reached max %s", count, MAX_COMPLETION_ITEMS));
            return true;
        }
        return false;
    }

    private Set<String> subPackages(String parentPackage) {
        var result = new HashSet<String>();
        Consumer<String> checkClassName =
                name -> {
                    var packageName = StringSearch.mostName(name);
                    if (packageName.startsWith(parentPackage) && packageName.length() > parentPackage.length()) {
                        var start = parentPackage.length() + 1;
                        var end = packageName.indexOf('.', start);
                        if (end == -1) end = packageName.length();
                        var prefix = packageName.substring(0, end);
                        result.add(prefix);
                    }
                };
        for (var name : parent.jdkClasses) checkClassName.accept(name);
        for (var name : parent.classPathClasses) checkClassName.accept(name);
        return result;
    }

    private void addKeywords(String[] keywords, String partialName, List<CompletionItem> result) {
        for (var k : keywords) {
            if (StringSearch.matchesPartialName(k, partialName)) {
                result.add(keywordCompletion(k));
            }
        }
    }

    private List<CompletionItem> completeScopeIdentifiers(
            Path file, int cursor, String partialName, boolean addParens, boolean addSemi) {
        var result = new ArrayList<CompletionItem>();
        var root = root(file);
        // Add locals
        var path = findPath(file, cursor);
        var scope = trees.getScope(path);
        if (scope.getEnclosingClass() == null) {
            var line = root.getLineMap().getLineNumber(cursor);
            LOG.warning(String.format("No enclosing class at %s(%d)", file, line));
            return List.of();
        }
        var ids = identifiers(root, scope, name -> StringSearch.matchesPartialName(name, partialName));
        var container = scope.getEnclosingClass().asType();
        result.addAll(groupByOverload(ids, addParens, addSemi, container));
        // Add classes
        var startsWithUpperCase = partialName.length() > 0 && Character.isUpperCase(partialName.charAt(0));
        if (startsWithUpperCase) {
            var packageName = Objects.toString(root.getPackageName(), "");
            Predicate<String> matchesPartialName =
                    qualifiedName -> {
                        var className = StringSearch.lastName(qualifiedName);
                        return StringSearch.matchesPartialName(className, partialName);
                    };
            // Check JDK
            LOG.info("...checking JDK");
            for (var c : parent.jdkClasses) {
                if (tooManyItems(result.size())) return result;
                if (!matchesPartialName.test(c)) continue;
                if (isSamePackage(c, packageName) || isPublicClassFile(c)) {
                    result.add(classNameCompletion(c, isImported(file, c)));
                }
            }
            // Check classpath
            LOG.info("...checking classpath");
            var classPathNames = new HashSet<String>();
            for (var c : parent.classPathClasses) {
                if (tooManyItems(result.size())) return result;
                if (!matchesPartialName.test(c)) continue;
                if (isSamePackage(c, packageName) || isPublicClassFile(c)) {
                    result.add(classNameCompletion(c, isImported(file, c)));
                    classPathNames.add(c);
                }
            }
            // Check sourcepath
            LOG.info("...checking source path");
            for (var toFile : FileStore.all()) {
                if (tooManyItems(result.size())) return result;
                // If file is in the same package, any class defined in the file is accessible
                var otherPackageName = FileStore.packageName(toFile);
                var samePackage = otherPackageName.equals(packageName) || otherPackageName.isEmpty();
                // If file is in a different package, only a class with the same name as the file is accessible
                var maybePublic = StringSearch.matchesPartialName(toFile.getFileName().toString(), partialName);
                if (samePackage || maybePublic) {
                    result.addAll(accessibleClasses(file, toFile, partialName, packageName, classPathNames));
                }
            }
        }
        return result;
    }

    private boolean isSamePackage(String className, String fromPackage) {
        return StringSearch.mostName(className).equals(fromPackage);
    }

    private boolean isPublicClassFile(String className) {
        try {
            var platform =
                    parent.fileManager.getJavaFileForInput(
                            StandardLocation.PLATFORM_CLASS_PATH, className, JavaFileObject.Kind.CLASS);
            if (platform != null) return isPublic(platform);
            var classpath =
                    parent.fileManager.getJavaFileForInput(
                            StandardLocation.CLASS_PATH, className, JavaFileObject.Kind.CLASS);
            if (classpath != null) return isPublic(classpath);
            return false;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean isPublic(JavaFileObject classFile) {
        try (var in = classFile.openInputStream()) {
            var header = ClassHeader.of(in);
            return header.isPublic;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private List<CompletionItem> accessibleClasses(
            Path fromFile, Path toFile, String partialName, String fromPackage, Set<String> skip) {
        var parse = Parser.parseFile(toFile);
        var classNames = parse.accessibleClasses(partialName, fromPackage);
        var result = new ArrayList<CompletionItem>();
        for (var name : classNames) {
            // If class was already autocompleted using the classpath, skip it
            if (skip.contains(name)) continue;
            // Otherwise, add this name!
            result.add(classNameCompletion(name, isImported(fromFile, name)));
        }
        return result;
    }

    private List<Element> staticImports(CompilationUnitTree root, Predicate<CharSequence> test) {
        var result = new ArrayList<Element>();
        for (var i : root.getImports()) {
            if (!i.isStatic()) continue;
            var id = (MemberSelectTree) i.getQualifiedIdentifier();
            var path = trees.getPath(root, id.getExpression());
            var el = (TypeElement) trees.getElement(path);
            if (id.getIdentifier().contentEquals("*")) {
                for (var member : el.getEnclosedElements()) {
                    if (test.test(member.getSimpleName()) && member.getModifiers().contains(Modifier.STATIC)) {
                        result.add(member);
                        if (tooManyItems(result)) return result;
                    }
                }
            } else {
                for (var member : el.getEnclosedElements()) {
                    if (test.test(member.getSimpleName()) && member.getModifiers().contains(Modifier.STATIC)) {
                        result.add(member);
                        if (tooManyItems(result)) return result;
                    }
                }
            }
        }
        return result;
    }

    /** Find the smallest tree that includes the cursor */
    TreePath findPath(Path file, long cursor) {
        var root = root(file);
        var finder = new FindSmallest(cursor, borrow.task, root);
        finder.scan(root, null);
        if (finder.found == null) {
            return new TreePath(root);
        }
        return finder.found;
    }

    private CompletionItem snippetCompletion(String label, String snippet) {
        var i = new CompletionItem();
        i.label = label;
        i.kind = CompletionItemKind.Snippet;
        i.insertText = snippet;
        i.insertTextFormat = InsertTextFormat.Snippet;
        i.sortText = String.format("%02d%s", Priority.SNIPPET, i.label);
        return i;
    }

    private CompletionItem varCompletion(VariableElement e, TypeMirror container) {
        var i = new CompletionItem();
        i.label = e.getSimpleName().toString();
        i.kind = completionItemKind(e);
        i.detail = ShortTypePrinter.DEFAULT.print(e.asType());
        i.sortText = String.format("%02d%s", varPriority(e, container), i.label);
        i.data = data(new Ptr(e), 0);
        return i;
    }

    private CompletionItem innerTypeCompletion(TypeElement e, TypeMirror container) {
        var i = new CompletionItem();
        i.label = e.getSimpleName().toString();
        i.kind = completionItemKind(e);
        i.detail = ShortTypePrinter.DEFAULT.print(e.asType());
        i.sortText = String.format("%02d%s", innerClassPriority(e, container), i.label);
        i.data = data(new Ptr(e), 0);
        return i;
    }

    private CompletionItem methodCompletion(
            List<ExecutableElement> methods, boolean addParens, boolean addSemi, TypeMirror container) {
        var first = methods.get(0);
        var i = new CompletionItem();
        i.label = first.getSimpleName().toString();
        i.kind = completionItemKind(first);
        i.filterText = first.getSimpleName().toString();
        i.sortText = String.format("%02d%s", methodPriority(first, container), first.getSimpleName().toString());
        // Try to be as helpful as possible with insertText
        if (addParens) {
            if (methods.size() == 1 && first.getParameters().isEmpty()) {
                if (addSemi && first.getReturnType().getKind() == TypeKind.VOID) {
                    i.insertText = first.getSimpleName() + "();$0";
                } else {
                    i.insertText = first.getSimpleName() + "()$0";
                }
            } else {
                var allReturnVoid = true;
                for (var m : methods) {
                    allReturnVoid = allReturnVoid && m.getReturnType().getKind() == TypeKind.VOID;
                }
                if (addSemi && allReturnVoid) {
                    i.insertText = first.getSimpleName() + "($0);";
                } else {
                    i.insertText = first.getSimpleName() + "($0)";
                }
                // Activate signatureHelp
                // Remove this if VSCode ever fixes https://github.com/microsoft/vscode/issues/78806
                i.command = new Command();
                i.command.command = "editor.action.triggerParameterHints";
            }
            i.insertTextFormat = 2; // Snippet
        } else {
            i.insertText = first.getSimpleName().toString();
        }
        // Save pointer for method and class doc resultion
        i.data = data(new Ptr(first), methods.size() - 1);
        return i;
    }

    private Integer completionItemKind(Element e) {
        switch (e.getKind()) {
            case ANNOTATION_TYPE:
                return CompletionItemKind.Interface;
            case CLASS:
                return CompletionItemKind.Class;
            case CONSTRUCTOR:
                return CompletionItemKind.Constructor;
            case ENUM:
                return CompletionItemKind.Enum;
            case ENUM_CONSTANT:
                return CompletionItemKind.EnumMember;
            case EXCEPTION_PARAMETER:
                return CompletionItemKind.Property;
            case FIELD:
                return CompletionItemKind.Field;
            case STATIC_INIT:
            case INSTANCE_INIT:
                return CompletionItemKind.Function;
            case INTERFACE:
                return CompletionItemKind.Interface;
            case LOCAL_VARIABLE:
                return CompletionItemKind.Variable;
            case METHOD:
                return CompletionItemKind.Method;
            case PACKAGE:
                return CompletionItemKind.Module;
            case PARAMETER:
                return CompletionItemKind.Property;
            case RESOURCE_VARIABLE:
                return CompletionItemKind.Variable;
            case TYPE_PARAMETER:
                return CompletionItemKind.TypeParameter;
            case OTHER:
            default:
                return null;
        }
    }

    private CompletionItem keywordCompletion(String keyword) {
        var i = new CompletionItem();
        i.label = keyword;
        i.kind = CompletionItemKind.Keyword;
        i.detail = "keyword";
        i.sortText = String.format("%02d%s", Priority.KEYWORD, i.label);
        return i;
    }

    private CompletionItem packageCompletion(Element member) {
        var i = new CompletionItem();
        i.label = member.getSimpleName().toString();
        i.kind = completionItemKind(member);
        i.detail = member.toString();
        i.sortText = String.format("%02d%s", Priority.PACKAGE_MEMBER, i.label);
        i.data = data(new Ptr(member), 0);
        return i;
    }

    private CompletionItem packageCompletion(String fullName, String name) {
        var i = new CompletionItem();
        i.label = name;
        i.kind = CompletionItemKind.Module;
        i.detail = fullName;
        i.sortText = String.format("%02d%s", Priority.PACKAGE_MEMBER, i.label);
        return i;
    }

    private CompletionItem caseCompletion(Element member) {
        var i = new CompletionItem();
        i.label = member.getSimpleName().toString();
        i.kind = completionItemKind(member);
        i.detail = member.toString();
        i.sortText = String.format("%02d%s", Priority.CASE_LABEL, i.label);
        i.data = data(new Ptr(member), 0);
        return i;
    }

    private CompletionItem classNameCompletion(String name, boolean isImported) {
        var i = new CompletionItem();
        i.label = StringSearch.lastName(name);
        i.kind = CompletionItemKind.Class;
        i.detail = name;
        if (isImported) {
            i.sortText = String.format("%02d%s", Priority.IMPORTED_CLASS, i.label);
        } else {
            i.sortText = String.format("%02d%s", Priority.NOT_IMPORTED_CLASS, i.label);
        }
        return i;
    }

    private int methodPriority(ExecutableElement e, TypeMirror container) {
        var declared = (TypeElement) e.getEnclosingElement();
        if (declared.equals(container)) {
            return Priority.METHOD;
        } else if (declared.getQualifiedName().contentEquals("java.lang.Object")) {
            return Priority.OBJECT_METHOD;
        } else {
            return Priority.INHERITED_METHOD;
        }
    }

    private int varPriority(VariableElement e, TypeMirror container) {
        if (container instanceof TypeElement) {
            var declared = (TypeElement) e.getEnclosingElement();
            if (declared.equals(container)) {
                return Priority.FIELD;
            } else {
                return Priority.INHERITED_FIELD;
            }
        } else {
            return Priority.LOCAL;
        }
    }

    private int innerClassPriority(TypeElement e, TypeMirror container) {
        var enclosing = e.getEnclosingElement();
        if (!(enclosing instanceof TypeElement)) {
            return Priority.INNER_CLASS;
        }
        var declared = (TypeElement) enclosing;
        if (declared.equals(container)) {
            return Priority.INNER_CLASS;
        } else {
            return Priority.INHERITED_INNER_CLASS;
        }
    }

    private static class Priority {
        static int iota = 0;
        static final int SNIPPET = iota;
        static final int LOCAL = iota++;
        static final int FIELD = iota++;
        static final int INHERITED_FIELD = iota++;
        static final int METHOD = iota++;
        static final int INHERITED_METHOD = iota++;
        static final int OBJECT_METHOD = iota++;
        static final int INNER_CLASS = iota++;
        static final int INHERITED_INNER_CLASS = iota++;
        static final int IMPORTED_CLASS = iota++;
        static final int NOT_IMPORTED_CLASS = iota++;
        static final int KEYWORD = iota++;
        static final int PACKAGE_MEMBER = iota++;
        static final int CASE_LABEL = iota++;
    }

    private JsonElement data(Ptr ptr, int plusOverloads) {
        var data = new CompletionData();
        data.ptr = ptr;
        data.plusOverloads = plusOverloads;
        return JavaLanguageServer.GSON.toJsonTree(data);
    }

    Map<Path, ColorsHolder> colors() {
        var result = new HashMap<Path, ColorsHolder>();
        for (var root : roots) {
            var path = Paths.get(root.getSourceFile().toUri());
            var colorizer = new Colorizer(borrow.task);
            colorizer.scan(root, null);
            result.put(path, colorizer.colors);
        }
        return result;
    }

    private static final Logger LOG = Logger.getLogger("main");
}
