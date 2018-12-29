package org.javacs_server;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.lang.model.element.*;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

public class CompileFocus {
    public static final int MAX_COMPLETION_ITEMS = 50;

    private final JavaCompilerService parent;
    private final URI file;
    private final String contents;
    private final int line, character;
    private final JavacTask task;
    private final Trees trees;
    private final CompilationUnitTree root;
    private final TreePath path;

    CompileFocus(JavaCompilerService parent, URI file, String contents, int line, int character) {
        this.parent = parent;
        this.file = file;
        this.contents = new Pruner(file, contents).prune(line, character);
        this.line = line;
        this.character = character;
        this.task = singleFileTask(parent, file, this.contents);
        this.trees = Trees.instance(task);

        var profiler = new Profiler();
        task.addTaskListener(profiler);
        try {
            var it = task.parse().iterator();
            this.root = it.hasNext() ? it.next() : null; // TODO something better than null when no class is present
            // The results of task.analyze() are unreliable when errors are present
            // You can get at `Element` values using `Trees`
            task.analyze();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        profiler.print();
        this.path = findPath(task, root, line, character);
    }

    /** Create a task that compiles a single file */
    static JavacTask singleFileTask(JavaCompilerService parent, URI file, String contents) {
        parent.diags.clear();
        return (JavacTask)
                parent.compiler.getTask(
                        null,
                        parent.fileManager,
                        parent.diags::add,
                        JavaCompilerService.options(parent.sourcePath, parent.classPath),
                        Collections.emptyList(),
                        Collections.singletonList(new StringFileObject(contents, file)));
    }

    public Optional<URI> declaringFile(Element e) {
        var top = topLevelDeclaration(e);
        if (!top.isPresent()) return Optional.empty();
        return findDeclaringFile(top.get());
    }

    /** Find the smallest element that includes the cursor */
    public Element element() {
        return trees.getElement(path);
    }

    /** Find all overloads for the smallest method call that includes the cursor */
    public Optional<MethodInvocation> methodInvocation() {
        LOG.info(String.format("Find method invocation around %s(%d,%d)...", file, line, character));

        for (var path = this.path; path != null; path = path.getParentPath()) {
            if (path.getLeaf() instanceof MethodInvocationTree) {
                // Find all overloads of method
                LOG.info(String.format("...`%s` is a method invocation", path.getLeaf()));
                var invoke = (MethodInvocationTree) path.getLeaf();
                var method = trees.getElement(trees.getPath(path.getCompilationUnit(), invoke.getMethodSelect()));
                var results = new ArrayList<ExecutableElement>();
                for (var m : method.getEnclosingElement().getEnclosedElements()) {
                    if (m.getKind() == ElementKind.METHOD && m.getSimpleName().equals(method.getSimpleName())) {
                        results.add((ExecutableElement) m);
                    }
                }
                // Figure out which parameter is active
                var activeParameter = invoke.getArguments().indexOf(this.path.getLeaf());
                LOG.info(String.format("...active parameter `%s` is %d", this.path.getLeaf(), activeParameter));
                // Figure out which method is active, if possible
                Optional<ExecutableElement> activeMethod =
                        method instanceof ExecutableElement
                                ? Optional.of((ExecutableElement) method)
                                : Optional.empty();
                return Optional.of(new MethodInvocation(invoke, activeMethod, activeParameter, results));
            } else if (path.getLeaf() instanceof NewClassTree) {
                // Find all overloads of method
                LOG.info(String.format("...`%s` is a constructor invocation", path.getLeaf()));
                var invoke = (NewClassTree) path.getLeaf();
                var method = trees.getElement(path);
                var results = new ArrayList<ExecutableElement>();
                for (var m : method.getEnclosingElement().getEnclosedElements()) {
                    if (m.getKind() == ElementKind.CONSTRUCTOR) {
                        results.add((ExecutableElement) m);
                    }
                }
                // Figure out which parameter is active
                var activeParameter = invoke.getArguments().indexOf(this.path.getLeaf());
                LOG.info(String.format("...active parameter `%s` is %d", this.path.getLeaf(), activeParameter));
                // Figure out which method is active, if possible
                Optional<ExecutableElement> activeMethod =
                        method instanceof ExecutableElement
                                ? Optional.of((ExecutableElement) method)
                                : Optional.empty();
                return Optional.of(new MethodInvocation(invoke, activeMethod, activeParameter, results));
            }
        }
        return Optional.empty();
    }

    public List<Completion> completeIdentifiers(boolean insideClass, boolean insideMethod, String partialName) {
        LOG.info(String.format("Completing identifiers starting with `%s`...", partialName));

        var result = new ArrayList<Completion>();

        // Add snippets
        if (!insideClass) {
            // If no package declaration is present, suggest package [inferred name];
            if (root.getPackage() == null) {
                relativeToSourcePath(file)
                        .ifPresent(
                                relative -> {
                                    var name = relative.toString().replace(File.separatorChar, '.');
                                    result.add(Completion.ofSnippet("package " + name, "package " + name + ";\n\n"));
                                });
            }
            // If no class declaration is present, suggest class [file name]
            var hasClassDeclaration = false;
            for (var t : root.getTypeDecls()) {
                if (!(t instanceof ErroneousTree)) {
                    hasClassDeclaration = true;
                }
            }
            if (!hasClassDeclaration) {
                var name = Paths.get(file).getFileName().toString();
                name = name.substring(0, name.length() - ".java".length());
                result.add(Completion.ofSnippet("class " + name, "class " + name + " {\n    $0\n}"));
            }
        }
        // Add identifiers
        completeScopeIdentifiers(partialName, result);
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

    public List<Completion> completeAnnotations(String partialName) {
        var result = new ArrayList<Completion>();
        // Add @Override ... snippet
        if ("Override".startsWith(partialName)) {
            // TODO filter out already-implemented methods using thisMethods
            var alreadyShown = new HashSet<String>();
            for (var method : superMethods()) {
                var mods = method.getModifiers();
                if (mods.contains(Modifier.STATIC) || mods.contains(Modifier.PRIVATE)) continue;

                var label = "@Override " + ShortTypePrinter.printMethod(method);
                var snippet = "Override\n" + new TemplatePrinter().printMethod(method) + " {\n    $0\n}";
                var override = Completion.ofSnippet(label, snippet);
                if (!alreadyShown.contains(label)) {
                    result.add(override);
                    alreadyShown.add(label);
                }
            }
        }
        // Add @Override, @Test, other simple class names
        completeScopeIdentifiers(partialName, result);
        return result;
    }

    /** Find all case options in the switch expression surrounding line:character */
    public List<Completion> completeCases() {
        LOG.info(String.format("Complete enum constants following `%s`...", path.getLeaf()));

        // Find surrounding switch
        var path = this.path;
        while (!(path.getLeaf() instanceof SwitchTree)) path = path.getParentPath();
        var leaf = (SwitchTree) path.getLeaf();
        path = new TreePath(path, leaf.getExpression());
        LOG.info(String.format("...found switch expression `%s`", leaf.getExpression()));

        // Get members of switched type
        var type = trees.getTypeMirror(path);
        LOG.info(String.format("...switched expression has type `%s`", type));
        var types = task.getTypes();
        var definition = types.asElement(type);
        LOG.info(String.format("...switched expression has definition `%s`", definition));
        var result = new ArrayList<Completion>();
        for (var member : definition.getEnclosedElements()) {
            if (member.getKind() == ElementKind.ENUM_CONSTANT) result.add(Completion.ofElement(member));
        }

        return result;
    }

    /** Find all members of expression ending at line:character */
    public List<Completion> completeMembers(boolean isReference) {
        var types = task.getTypes();
        var scope = trees.getScope(path);
        var element = trees.getElement(path);

        if (element instanceof PackageElement) {
            var result = new ArrayList<Completion>();
            var p = (PackageElement) element;

            LOG.info(String.format("...completing members of package %s", p.getQualifiedName()));

            // Add class-names resolved as Element by javac
            for (var member : p.getEnclosedElements()) {
                // If the package member is a TypeElement, like a class or interface, check if it's accessible
                if (member instanceof TypeElement) {
                    if (trees.isAccessible(scope, (TypeElement) member)) {
                        result.add(Completion.ofElement(member));
                    }
                }
                // Otherwise, just assume it's accessible and add it to the list
                else result.add(Completion.ofElement(member));
            }
            // Add sub-package names resolved as String by guava ClassPath
            var parent = p.getQualifiedName().toString();
            var subs = subPackages(parent);
            for (var sub : subs) {
                result.add(Completion.ofPackagePart(sub, Parser.lastName(sub)));
            }

            return result;
        } else if (element instanceof TypeElement && isReference) {
            var result = new ArrayList<Completion>();
            var t = (TypeElement) element;

            LOG.info(String.format("...completing static methods of %s", t.getQualifiedName()));

            // Add members
            for (var member : t.getEnclosedElements()) {
                if (member.getKind() == ElementKind.METHOD
                        && trees.isAccessible(scope, member, (DeclaredType) t.asType())) {
                    result.add(Completion.ofElement(member));
                }
            }

            // Add ::new
            result.add(Completion.ofKeyword("new"));

            return result;
        } else if (element instanceof TypeElement && !isReference) {
            var result = new ArrayList<Completion>();
            var t = (TypeElement) element;

            LOG.info(String.format("...completing static members of %s", t.getQualifiedName()));

            // Add static members
            for (var member : t.getEnclosedElements()) {
                // TODO if this is a member reference :: then include non-statics
                if (member.getModifiers().contains(Modifier.STATIC)
                        && trees.isAccessible(scope, member, (DeclaredType) t.asType())) {
                    result.add(Completion.ofElement(member));
                }
            }

            // Add .class
            result.add(Completion.ofKeyword("class"));
            result.add(Completion.ofKeyword("this"));
            result.add(Completion.ofKeyword("super"));

            return result;
        } else {
            var type = trees.getTypeMirror(path);
            if (hasMembers(type)) {
                LOG.info(String.format("...completing virtual members of %s", type));

                var result = new ArrayList<Completion>();
                var ts = supersWithSelf(type);
                var alreadyAdded = new HashSet<String>();
                for (var t : ts) {
                    var e = types.asElement(t);
                    if (e != null) {
                        for (var member : e.getEnclosedElements()) {
                            // Don't add statics
                            if (member.getModifiers().contains(Modifier.STATIC)) continue;
                            // Don't add constructors
                            if (member.getSimpleName().contentEquals("<init>")) continue;
                            // Skip overridden members from superclass
                            if (alreadyAdded.contains(member.toString())) continue;

                            // If type is a DeclaredType, check accessibility of member
                            if (t instanceof DeclaredType) {
                                if (trees.isAccessible(scope, member, (DeclaredType) t)) {
                                    result.add(Completion.ofElement(member));
                                }
                            }
                            // Otherwise, accessibility rules are very complicated
                            // Give up and just declare that everything is accessible
                            else result.add(Completion.ofElement(member));
                            // Remember the signature of the added method, so we don't re-add it later
                            alreadyAdded.add(member.toString());
                        }
                    }

                    if (t instanceof ArrayType) {
                        result.add(Completion.ofKeyword("length"));
                    }
                }
                return result;
            } else {
                LOG.warning("...don't know how to complete members of type " + type);
                return Collections.emptyList();
            }
        }
    }

    public static String[] TOP_LEVEL_KEYWORDS = {
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

    private Optional<Path> relativeToSourcePath(URI source) {
        var p = Paths.get(source);
        for (var root : parent.sourcePath) {
            if (p.startsWith(root)) {
                var rel = root.relativize(p.getParent());
                return Optional.of(rel);
            }
        }
        return Optional.empty();
    }

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

    private TypeMirror enclosingClass() {
        var path = this.path;
        while (!(path.getLeaf() instanceof ClassTree)) path = path.getParentPath();
        var enclosingClass = trees.getElement(path);

        return enclosingClass.asType();
    }

    private List<ExecutableElement> thisMethods() {
        var thisType = enclosingClass();
        var types = task.getTypes();
        var result = new ArrayList<ExecutableElement>();

        if (thisType instanceof DeclaredType) {
            var type = (DeclaredType) thisType;
            result.addAll(virtualMethods(type));
        }

        return result;
    }

    private void collectSuperMethods(TypeMirror thisType, List<ExecutableElement> result) {
        var types = task.getTypes();

        for (var superType : types.directSupertypes(thisType)) {
            if (superType instanceof DeclaredType) {
                var type = (DeclaredType) superType;
                result.addAll(virtualMethods(type));
                collectSuperMethods(type, result);
            }
        }
    }

    private List<ExecutableElement> superMethods() {
        var thisType = enclosingClass();
        var result = new ArrayList<ExecutableElement>();

        collectSuperMethods(thisType, result);

        return result;
    }

    static boolean matchesPartialName(CharSequence candidate, CharSequence partialName) {
        if (candidate.length() < partialName.length()) return false;
        for (int i = 0; i < partialName.length(); i++) {
            if (candidate.charAt(i) != partialName.charAt(i)) return false;
        }
        return true;
    }

    private boolean isImported(String qualifiedName) {
        var packageName = Parser.mostName(qualifiedName);
        var className = Parser.lastName(qualifiedName);
        for (var i : root.getImports()) {
            var importName = i.getQualifiedIdentifier().toString();
            var importPackage = Parser.mostName(importName);
            var importClass = Parser.lastName(importName);
            if (importClass.equals("*") && importPackage.equals(packageName)) return true;
            if (importClass.equals(className) && importPackage.equals(packageName)) return true;
        }
        return false;
    }

    private List<TypeMirror> supersWithSelf(TypeMirror t) {
        var elements = task.getElements();
        var types = task.getTypes();
        var result = new ArrayList<TypeMirror>();
        result.add(t);
        // Add members of superclasses and interfaces
        result.addAll(types.directSupertypes(t));
        // Object type is not included by default
        // We need to add it to get members like .equals(other) and .hashCode()
        // TODO this may add things twice for interfaces with no super-interfaces
        result.add(elements.getTypeElement("java.lang.Object").asType());
        return result;
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

    /** Find all identifiers in scope at line:character */
    List<Element> scopeMembers(String partialName) {
        var types = task.getTypes();
        var start = trees.getScope(path);

        class Walk {
            List<Element> result = new ArrayList<>();

            boolean isStatic(Scope s) {
                var method = s.getEnclosingMethod();
                if (method != null) {
                    return method.getModifiers().contains(Modifier.STATIC);
                } else return false;
            }

            boolean isStatic(Element e) {
                return e.getModifiers().contains(Modifier.STATIC);
            }

            boolean isThisOrSuper(Element e) {
                var name = e.getSimpleName();
                return name.contentEquals("this") || name.contentEquals("super");
            }

            // Place each member of `this` or `super` directly into `results`
            void unwrapThisSuper(VariableElement ve) {
                var thisType = ve.asType();
                // `this` and `super` should always be instances of DeclaredType, which we'll use to check accessibility
                if (!(thisType instanceof DeclaredType)) {
                    LOG.warning(String.format("%s is not a DeclaredType", thisType));
                    return;
                }
                var thisDeclaredType = (DeclaredType) thisType;
                var thisElement = types.asElement(thisDeclaredType);
                for (var thisMember : thisElement.getEnclosedElements()) {
                    if (isStatic(start) && !isStatic(thisMember)) continue;
                    if (thisMember.getSimpleName().contentEquals("<init>")) continue;
                    if (!matchesPartialName(thisMember.getSimpleName(), partialName)) continue;

                    // Check if member is accessible from original scope
                    if (trees.isAccessible(start, thisMember, thisDeclaredType)) {
                        result.add(thisMember);
                    }
                }
            }

            // Place each member of `s` into results, and unwrap `this` and `super`
            void walkLocals(Scope s) {
                try {
                    for (var e : s.getLocalElements()) {
                        if (matchesPartialName(e.getSimpleName(), partialName)) {
                            if (e instanceof TypeElement) {
                                var te = (TypeElement) e;
                                if (trees.isAccessible(start, te)) result.add(te);
                            } else if (isThisOrSuper(e)) {
                                if (!isStatic(s)) result.add(e);
                            } else {
                                result.add(e);
                            }
                        }
                        if (isThisOrSuper(e)) {
                            unwrapThisSuper((VariableElement) e);
                        }
                        if (tooManyItems(result.size())) return;
                    }
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "error walking locals in scope", e);
                }
            }

            // TODO profile info probably isn't necessary anymore now that we skip the slow stages
            StringBuilder profile = new StringBuilder();
            Instant started = Instant.now();

            void profileEnd(Scope s) {
                var ended = Instant.now();
                var elapsed = Duration.between(started, ended);
                var ms = elapsed.getSeconds() * 1000 + elapsed.getNano() / 1000 / 1000;
                profile.append("\n\t").append(s);
                profile.append(String.format("\n\t\t%dms", ms));
                started = ended;
            }

            void printProfile() {
                LOG.info(profile.toString());
            }

            // Walk each enclosing scope, placing its members into `results`
            List<Element> walkScopes() {
                var scopes = new ArrayList<Scope>();
                for (var s = start; s != null; s = s.getEnclosingScope()) {
                    scopes.add(s);
                }
                // Scopes may be contained in an enclosing scope.
                // The outermost scope contains those elements available via "star import" declarations;
                // the scope within that contains the top level elements of the compilation unit, including any named
                // imports.
                // https://parent.docs.oracle.com/en/java/javase/11/docs/api/jdk.compiler/com/sun/source/tree/Scope.html
                for (var i = 0; i < scopes.size() - 2; i++) {
                    var s = scopes.get(i);
                    walkLocals(s);
                    profileEnd(s);
                    // Return early?
                    if (tooManyItems(result.size())) {
                        printProfile();
                        return result;
                    }
                }

                printProfile();
                return result;
            }
        }
        return new Walk().walkScopes();
    }

    private boolean tooManyItems(int count) {
        var test = count >= MAX_COMPLETION_ITEMS;
        if (test) LOG.warning(String.format("...# of items %d reached max %s", count, MAX_COMPLETION_ITEMS));
        return test;
    }

    private Set<String> subPackages(String parentPackage) {
        var result = new HashSet<String>();
        Consumer<String> checkClassName =
                name -> {
                    var packageName = Parser.mostName(name);
                    if (packageName.startsWith(parentPackage) && packageName.length() > parentPackage.length()) {
                        var start = parentPackage.length() + 1;
                        var end = packageName.indexOf('.', start);
                        if (end == -1) end = packageName.length();
                        var prefix = packageName.substring(0, end);
                        result.add(prefix);
                    }
                };
        for (var name : parent.jdkClasses.classes()) checkClassName.accept(name);
        for (var name : parent.classPathClasses.classes()) checkClassName.accept(name);
        return result;
    }

    private static void addKeywords(String[] keywords, String partialName, List<Completion> result) {
        for (var k : keywords) {
            if (matchesPartialName(k, partialName)) {
                result.add(Completion.ofKeyword(k));
            }
        }
    }

    private void completeScopeIdentifiers(String partialName, List<Completion> result) {
        // Add locals
        var locals = scopeMembers(partialName);
        for (var m : locals) {
            result.add(Completion.ofElement(m));
        }
        LOG.info(String.format("...found %d locals", locals.size()));

        // Add static imports
        var staticImports = staticImports(file, contents, partialName);
        for (var m : staticImports) {
            result.add(Completion.ofElement(m));
        }
        LOG.info(String.format("...found %d static imports", staticImports.size()));

        // Add classes
        var startsWithUpperCase = partialName.length() > 0 && Character.isUpperCase(partialName.charAt(0));
        if (startsWithUpperCase) {
            var packageName = Objects.toString(root.getPackageName(), "");
            Predicate<String> matchesPartialName =
                    qualifiedName -> {
                        var className = Parser.lastName(qualifiedName);
                        return matchesPartialName(className, partialName);
                    };

            // Check JDK
            LOG.info("...checking JDK");
            for (var c : parent.jdkClasses.classes()) {
                if (tooManyItems(result.size())) return;
                if (matchesPartialName.test(c) && parent.jdkClasses.isAccessibleFromPackage(c, packageName)) {
                    result.add(Completion.ofClassName(c, isImported(c)));
                }
            }

            // Check classpath
            LOG.info("...checking classpath");
            for (var c : parent.classPathClasses.classes()) {
                if (tooManyItems(result.size())) return;
                if (matchesPartialName.test(c) && parent.classPathClasses.isAccessibleFromPackage(c, packageName)) {
                    result.add(Completion.ofClassName(c, isImported(c)));
                }
            }

            // Check sourcepath
            LOG.info("...checking source path");
            Predicate<Path> matchesFileName = file -> matchesPartialName(file.getFileName().toString(), partialName);
            Predicate<Path> isPublic =
                    file -> {
                        var fileName = file.getFileName().toString();
                        if (!fileName.endsWith(".java")) return true;
                        var simpleName = fileName.substring(0, fileName.length() - ".java".length());
                        Stream<String> lines;
                        try {
                            lines = Files.lines(file);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        return lines.anyMatch(line -> line.matches(".*public\\s+class\\s+" + simpleName + ".*"));
                    };
            for (var dir : parent.sourcePath) {
                Function<Path, String> qualifiedName =
                        file -> {
                            var relative = dir.relativize(file).toString().replace('/', '.');
                            if (!relative.endsWith(".java")) return "??? " + relative + " does not end in .java";
                            return relative.substring(0, relative.length() - ".java".length());
                        };
                for (var file : JavaCompilerService.javaSourcesInDir(dir)) {
                    if (tooManyItems(result.size())) return;
                    // Fast check, file name only
                    if (matchesFileName.test(file)) {
                        var c = qualifiedName.apply(file);
                        // Slow check, open file
                        if (matchesPartialName.test(c) && isPublic.test(file)) {
                            result.add(Completion.ofClassName(c, isImported(c)));
                        }
                    }
                }
            }
        }
    }

    private List<Element> staticImports(URI file, String contents, String partialName) {
        var result = new ArrayList<Element>();
        for (var i : root.getImports()) {
            if (!i.isStatic()) continue;
            var id = (MemberSelectTree) i.getQualifiedIdentifier();
            var path = trees.getPath(root, id.getExpression());
            var el = (TypeElement) trees.getElement(path);
            if (id.getIdentifier().contentEquals("*")) {
                for (var member : el.getEnclosedElements()) {
                    if (matchesPartialName(member.getSimpleName(), partialName)
                            && member.getModifiers().contains(Modifier.STATIC)) {
                        result.add(member);
                        if (tooManyItems(result.size())) return result;
                    }
                }
            } else {
                for (var member : el.getEnclosedElements()) {
                    if (matchesPartialName(member.getSimpleName(), partialName)
                            && member.getModifiers().contains(Modifier.STATIC)) {
                        result.add(member);
                        if (tooManyItems(result.size())) return result;
                    }
                }
            }
        }
        return result;
    }

    private Optional<TypeElement> topLevelDeclaration(Element e) {
        var parent = e;
        TypeElement result = null;
        while (parent.getEnclosingElement() != null) {
            if (parent instanceof TypeElement) result = (TypeElement) parent;
            parent = parent.getEnclosingElement();
        }
        return Optional.ofNullable(result);
    }

    private boolean containsTopLevelDeclaration(Path file, String simpleClassName) {
        var find = Pattern.compile("\\b(class|interface|enum) +" + simpleClassName + "\\b");
        try (var lines = Files.newBufferedReader(file)) {
            var line = lines.readLine();
            while (line != null) {
                if (find.matcher(line).find()) return true;
                line = lines.readLine();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return false;
    }

    /** Find the file `e` was declared in */
    private Optional<URI> findDeclaringFile(TypeElement e) {
        var name = e.getQualifiedName().toString();
        var lastDot = name.lastIndexOf('.');
        var packageName = lastDot == -1 ? "" : name.substring(0, lastDot);
        var className = name.substring(lastDot + 1);
        // First, look for a file named [ClassName].java
        var packagePath = Paths.get(packageName.replace('.', File.separatorChar));
        var publicClassPath = packagePath.resolve(className + ".java");
        for (var root : parent.sourcePath) {
            var absPath = root.resolve(publicClassPath);
            if (Files.exists(absPath) && containsTopLevelDeclaration(absPath, className)) {
                return Optional.of(absPath.toUri());
            }
        }
        // Then, look for a secondary declaration in all java files in the package
        var isPublic = e.getModifiers().contains(Modifier.PUBLIC);
        if (!isPublic) {
            for (var root : parent.sourcePath) {
                var absDir = root.resolve(packagePath);
                try {
                    var foundFile =
                            Files.list(absDir).filter(f -> containsTopLevelDeclaration(f, className)).findFirst();
                    if (foundFile.isPresent()) return foundFile.map(Path::toUri);
                } catch (IOException err) {
                    throw new RuntimeException(err);
                }
            }
        }
        return Optional.empty();
    }

    /** Find the smallest tree that includes the cursor */
    static TreePath findPath(JavacTask task, CompilationUnitTree root, int line, int character) {
        var trees = Trees.instance(task);
        var pos = trees.getSourcePositions();
        var cursor = root.getLineMap().getPosition(line, character);

        // Search for the smallest element that encompasses line:column
        class FindSmallest extends TreePathScanner<Void, Void> {
            TreePath found = null;

            boolean containsCursor(Tree tree) {
                long start = pos.getStartPosition(root, tree), end = pos.getEndPosition(root, tree);
                // If element has no position, give up
                if (start == -1 || end == -1) return false;
                // Check if `tree` contains line:column
                return start <= cursor && cursor <= end;
            }

            @Override
            public Void scan(Tree tree, Void nothing) {
                // This is pre-order traversal, so the deepest element will be the last one remaining in `found`
                if (containsCursor(tree)) found = new TreePath(getCurrentPath(), tree);
                super.scan(tree, nothing);
                return null;
            }

            @Override
            public Void visitErroneous(ErroneousTree node, Void nothing) {
                for (var t : node.getErrorTrees()) {
                    scan(t, nothing);
                }
                return null;
            }
        }
        var find = new FindSmallest();
        find.scan(root, null);
        if (find.found == null) {
            var uri = root.getSourceFile().toUri();
            var message = String.format("No TreePath to %s %d:%d", uri, line, character);
            throw new RuntimeException(message);
        }
        return find.found;
    }

    private static final Logger LOG = Logger.getLogger("main");
}
