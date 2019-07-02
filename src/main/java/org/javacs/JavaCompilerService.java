package org.javacs;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.lang.model.element.*;
import javax.tools.*;
import org.javacs.lsp.SymbolInformation;

// TODO eliminate uses of URI in favor of Path
class JavaCompilerService {
    // Not modifiable! If you want to edit these, you need to create a new instance
    final Set<Path> classPath, docPath;
    final Set<String> addExports;
    final ReusableCompiler compiler = new ReusableCompiler();
    final Docs docs;
    final Set<String> jdkClasses = Classes.jdkTopLevelClasses(), classPathClasses;
    // Diagnostics from the last compilation task
    final List<Diagnostic<? extends JavaFileObject>> diags = new ArrayList<>();
    // Use the same file manager for multiple tasks, so we don't repeatedly re-compile the same files
    // TODO intercept files that aren't in the batch and erase method bodies so compilation is faster
    final SourceFileManager fileManager;

    JavaCompilerService(Set<Path> classPath, Set<Path> docPath, Set<String> addExports) {
        System.err.println("Class path:");
        for (var p : classPath) {
            System.err.println("  " + p);
        }
        System.err.println("Doc path:");
        for (var p : docPath) {
            System.err.println("  " + p);
        }
        // classPath can't actually be modified, because JavaCompiler remembers it from task to task
        this.classPath = Collections.unmodifiableSet(classPath);
        this.docPath = Collections.unmodifiableSet(docPath);
        this.addExports = Collections.unmodifiableSet(addExports);
        this.docs = new Docs(docPath);
        this.classPathClasses = Classes.classPathTopLevelClasses(classPath);
        this.fileManager = new SourceFileManager();
    }

    /** Combine source path or class path entries using the system separator, for example ':' in unix */
    private static String joinPath(Collection<Path> classOrSourcePath) {
        return classOrSourcePath.stream().map(p -> p.toString()).collect(Collectors.joining(File.pathSeparator));
    }

    static List<String> options(Set<Path> classPath, Set<String> addExports) {
        var list = new ArrayList<String>();

        Collections.addAll(list, "-classpath", joinPath(classPath));
        // Collections.addAll(list, "-verbose");
        Collections.addAll(list, "-proc:none");
        Collections.addAll(list, "-g");
        // You would think we could do -Xlint:all,
        // but some lints trigger fatal errors in the presence of parse errors
        Collections.addAll(
                list,
                "-Xlint:cast",
                "-Xlint:deprecation",
                "-Xlint:empty",
                "-Xlint:fallthrough",
                "-Xlint:finally",
                "-Xlint:path",
                "-Xlint:unchecked",
                "-Xlint:varargs",
                "-Xlint:static");
        for (var export : addExports) {
            list.add("--add-exports");
            list.add(export + "=ALL-UNNAMED");
        }

        return list;
    }

    Docs docs() {
        return docs;
    }

    ParseFile parseFile(URI file) {
        return new ParseFile(this, file);
    }

    ParseFile parseJavaFileObject(JavaFileObject file) {
        return new ParseFile(this, file);
    }

    CompileBatch compileFocus(URI uri, int line, int character) {
        var contents = Pruner.prune(uri, line, character);
        var file = new SourceFileObject(uri, contents, Instant.now());
        return compileBatch(List.of(file));
    }

    CompileBatch compileFile(URI uri) {
        return compileUris(Collections.singleton(uri));
    }

    CompileBatch compileUris(Collection<URI> uris) {
        var files = new ArrayList<File>();
        for (var p : uris) files.add(new File(p));
        var sources = fileManager.getJavaFileObjectsFromFiles(files);
        var list = new ArrayList<JavaFileObject>();
        for (var s : sources) list.add(s);
        return new CompileBatch(this, list);
    }

    CompileBatch compileBatch(Collection<? extends JavaFileObject> sources) {
        return new CompileBatch(this, sources);
    }

    Set<URI> potentialDefinitions(Element to) {
        LOG.info(String.format("Find potential definitions of `%s`...", to));

        // If `to` is private, any definitions must be in the same file
        if (to.getModifiers().contains(Modifier.PRIVATE)) {
            LOG.info(String.format("...`%s` is private", to));
            var set = new HashSet<URI>();
            declaringFile(to).ifPresent(set::add);
            return set;
        }

        if (to instanceof ExecutableElement) {
            var allFiles = possibleFiles(to);

            // TODO this needs to use open text if available
            // Check if the file contains the name of `to`
            var hasWord = containsWord(allFiles, to);
            // Parse each file and check if the syntax tree is consistent with a definition of `to`
            // This produces some false positives, but parsing is much faster than compiling,
            // so it's an effective optimization
            var findName = simpleName(to);
            var checkTree = new HashSet<URI>();
            class FindMethod extends TreePathScanner<Void, Void> {
                private Name className;

                @Override
                public Void visitClass(ClassTree t, Void __) {
                    var prev = className;
                    className = t.getSimpleName();
                    super.visitClass(t, null);
                    className = prev;
                    return null;
                }

                @Override
                public Void visitMethod(MethodTree t, Void __) {
                    // TODO try to disprove that this is a reference by looking at obvious special cases, like is the
                    // simple name of the type different?
                    var match =
                            t.getName().contentEquals(findName)
                                    || t.getName().contentEquals("<init>") && className.contentEquals(findName);
                    if (match) {
                        var uri = getCurrentPath().getCompilationUnit().getSourceFile().toUri();
                        checkTree.add(uri);
                    }
                    return super.visitMethod(t, null);
                }
            }
            for (var f : hasWord) {
                var root = Parser.parse(f);
                new FindMethod().scan(root, null);
            }
            LOG.info(String.format("...%d files contain method `%s`", checkTree.size(), findName));
            return checkTree;
        } else {
            var files = new HashSet<URI>();
            declaringFile(to).ifPresent(files::add);
            return files;
        }
    }

    Set<URI> potentialReferences(Element to) {
        LOG.info(String.format("Find potential references to `%s`...", to));

        // If `to` is private, any definitions must be in the same file
        if (to.getModifiers().contains(Modifier.PRIVATE)) {
            LOG.info(String.format("...`%s` is private", to));
            var set = new HashSet<URI>();
            declaringFile(to).ifPresent(set::add);
            return set;
        }

        var findName = simpleName(to);
        var isField = to instanceof VariableElement && to.getEnclosingElement() instanceof TypeElement;
        var isType = to instanceof TypeElement;
        if (isField || isType) {
            LOG.info(String.format("...find identifiers named `%s`", findName));
            class FindVar extends TreePathScanner<Void, Set<URI>> {
                void add(Set<URI> found) {
                    var uri = getCurrentPath().getCompilationUnit().getSourceFile().toUri();
                    found.add(uri);
                }

                boolean method() {
                    var leaf = getCurrentPath().getLeaf();
                    var parent = getCurrentPath().getParentPath().getLeaf();
                    if (parent instanceof MethodInvocationTree) {
                        var method = (MethodInvocationTree) parent;
                        return method.getMethodSelect() == leaf;
                    }
                    return false;
                }

                @Override
                public Void visitIdentifier(IdentifierTree t, Set<URI> found) {
                    // TODO try to disprove that this is a reference by looking at obvious special cases, like is the
                    // simple name of the type different?
                    if (t.getName().contentEquals(findName) && !method()) add(found);
                    return super.visitIdentifier(t, found);
                }

                @Override
                public Void visitMemberSelect(MemberSelectTree t, Set<URI> found) {
                    if (t.getIdentifier().contentEquals(findName) && !method()) add(found);
                    return super.visitMemberSelect(t, found);
                }
            }
            return scanForPotentialReferences(to, new FindVar());
        } else if (to instanceof ExecutableElement) {
            LOG.info(String.format("...find method calls named `%s`", findName));
            class FindMethod extends TreePathScanner<Void, Set<URI>> {
                void add(Set<URI> found) {
                    var uri = getCurrentPath().getCompilationUnit().getSourceFile().toUri();
                    found.add(uri);
                }

                boolean isName(Tree t) {
                    if (t instanceof MemberSelectTree) {
                        var select = (MemberSelectTree) t;
                        return select.getIdentifier().contentEquals(findName);
                    }
                    if (t instanceof IdentifierTree) {
                        var id = (IdentifierTree) t;
                        return id.getName().contentEquals(findName);
                    }
                    if (t instanceof ParameterizedTypeTree) {
                        var param = (ParameterizedTypeTree) t;
                        return isName(param.getType());
                    }
                    return false;
                }

                @Override
                public Void visitMethodInvocation(MethodInvocationTree t, Set<URI> found) {
                    // TODO try to disprove that this is a reference by looking at obvious special cases, like is the
                    // simple name of the type different?
                    var method = t.getMethodSelect();
                    if (isName(method)) add(found);
                    // Check other parts
                    return super.visitMethodInvocation(t, found);
                }

                @Override
                public Void visitMemberReference(MemberReferenceTree t, Set<URI> found) {
                    if (t.getName().contentEquals(findName)) add(found);
                    return super.visitMemberReference(t, found);
                }

                @Override
                public Void visitNewClass(NewClassTree t, Set<URI> found) {
                    var cls = t.getIdentifier();
                    if (isName(cls)) add(found);
                    return super.visitNewClass(t, found);
                }
            }
            return scanForPotentialReferences(to, new FindMethod());
        } else {
            // Fields, type parameters can only be referenced from within the same file
            LOG.info(String.format("...references to `%s` must be in the same file", to));
            var files = new HashSet<URI>();
            var toFile = declaringFile(to);
            // If there is no declaring file
            if (!toFile.isPresent()) {
                LOG.info("..has no declaring file");
                return files;
            }
            // If the declaring file isn't a normal file, for example if it's in src.zip
            if (!FileStore.isJavaFile(toFile.get())) {
                LOG.info(String.format("...%s is not on the source path", toFile.get()));
                return files;
            }
            // Otherwise, jump to the declaring file
            LOG.info(String.format("...declared in %s", toFile.get().getPath()));
            files.add(toFile.get());
            return files;
        }
    }

    private static CharSequence simpleName(Element e) {
        if (e.getSimpleName().contentEquals("<init>")) {
            return e.getEnclosingElement().getSimpleName();
        }
        return e.getSimpleName();
    }

    private boolean isPackagePrivate(Element to) {
        return !to.getModifiers().contains(Modifier.PROTECTED) && !to.getModifiers().contains(Modifier.PUBLIC);
    }

    private Set<URI> scanForPotentialReferences(Element to, TreePathScanner<Void, Set<URI>> scan) {
        var allFiles = possibleFiles(to);

        // TODO this needs to use open text if available
        // Check if the file contains the name of `to`
        var hasWord = containsWord(allFiles, to);

        // You can't reference a TypeElement without importing it
        if (to instanceof TypeElement) {
            hasWord = containsImport(hasWord, (TypeElement) to);
        }

        // Parse each file and check if the syntax tree is consistent with a definition of `to`
        // This produces some false positives, but parsing is much faster than compiling,
        // so it's an effective optimization
        var found = new HashSet<URI>();
        for (var f : hasWord) {
            var root = Parser.parse(f);
            scan.scan(root, found);
        }
        LOG.info(String.format("...%d files contain matching syntax", found.size()));

        return found;
    }

    private Optional<URI> declaringFile(Element e) {
        // Find top-level type surrounding `to`
        LOG.info(String.format("...looking up declaring file of `%s`...", e));
        var top = topLevelDeclaration(e);
        if (!top.isPresent()) {
            LOG.warning("...no top-level type!");
            return Optional.empty();
        }
        // Find file by looking at package and class name
        LOG.info(String.format("...top-level type is %s", top.get()));
        var file = findDeclaringFile(top.get());
        if (!file.isPresent()) {
            LOG.info(String.format("...couldn't find declaring file for type"));
            return Optional.empty();
        }
        return file;
    }

    private Optional<TypeElement> topLevelDeclaration(Element e) {
        if (e == null) return Optional.empty();
        var parent = e;
        TypeElement result = null;
        while (parent.getEnclosingElement() != null) {
            if (parent instanceof TypeElement) result = (TypeElement) parent;
            parent = parent.getEnclosingElement();
        }
        return Optional.ofNullable(result);
    }

    /** Find the file `e` was declared in */
    private Optional<URI> findDeclaringFile(TypeElement e) {
        var name = e.getQualifiedName().toString();
        JavaFileObject file;
        try {
            file = fileManager.getJavaFileForInput(StandardLocation.SOURCE_PATH, name, JavaFileObject.Kind.SOURCE);
        } catch (IOException err) {
            throw new RuntimeException(err);
        }
        if (file == null) return Optional.empty();
        return Optional.of(file.toUri());
    }

    private Collection<Path> possibleFiles(Element to) {
        // If `to` is package-private, only look in my own package
        if (isPackagePrivate(to)) {
            var myPkg = packageName(to);
            var allFiles = FileStore.list(myPkg);
            LOG.info(String.format("...check %d files in my own package %s", allFiles.size(), myPkg));
            return allFiles;
        }
        // Otherwise search all files
        var allFiles = FileStore.all();
        LOG.info(String.format("...check %d files", allFiles.size()));
        return allFiles;
    }

    private static Cache<String, Boolean> cacheContainsWord = new Cache<>();

    private List<Path> containsWord(Collection<Path> allFiles, Element to) {
        // Figure out what name we're looking for
        var name = to.getSimpleName().toString();
        if (name.equals("<init>")) name = to.getEnclosingElement().getSimpleName().toString();
        if (!name.matches("\\w*")) throw new RuntimeException(String.format("`%s` is not a word", name));

        // Figure out all files that need to be re-scanned
        var outOfDate = new ArrayList<Path>();
        for (var file : allFiles) {
            if (cacheContainsWord.needs(file, name)) {
                outOfDate.add(file);
            }
        }

        // Update those files in cacheContainsWord
        LOG.info(String.format("...scanning %d out-of-date files for the word `%s`", outOfDate.size(), name));
        for (var file : outOfDate) {
            // TODO this needs to use open text if available
            var found = StringSearch.containsWord(file, name);
            cacheContainsWord.load(file, name, found);
        }

        // Assemble list of all files that contain name
        var hasWord = new ArrayList<Path>();
        for (var file : allFiles) {
            if (cacheContainsWord.get(file, name)) {
                hasWord.add(file);
            }
        }
        LOG.info(String.format("...%d files contain the word `%s`", hasWord.size(), name));

        return hasWord;
    }

    private static Cache<String, Boolean> cacheContainsImport = new Cache<>();

    private List<Path> containsImport(Collection<Path> allFiles, TypeElement to) {
        // Figure out which files import `to`, explicitly or implicitly
        var qName = to.getQualifiedName().toString();
        var toPackage = packageName(to);
        var toClass = className(to);
        var hasImport = new ArrayList<Path>();
        for (var file : allFiles) {
            if (cacheContainsImport.needs(file, qName)) {
                var found = StringSearch.containsImport(file, toPackage, toClass);
                cacheContainsImport.load(file, qName, found);
            }
            if (cacheContainsImport.get(file, qName)) {
                hasImport.add(file);
            }
        }
        LOG.info(String.format("...%d files import %s.%s", hasImport.size(), toPackage, toClass));

        return hasImport;
    }

    static String packageName(Element e) {
        while (e != null) {
            if (e instanceof PackageElement) {
                var pkg = (PackageElement) e;
                return pkg.getQualifiedName().toString();
            }
            e = e.getEnclosingElement();
        }
        return "";
    }

    static String className(Element e) {
        while (e != null) {
            if (e instanceof TypeElement) {
                var type = (TypeElement) e;
                return type.getSimpleName().toString();
            }
            e = e.getEnclosingElement();
        }
        return "";
    }

    static String className(TreePath t) {
        while (t != null) {
            if (t.getLeaf() instanceof ClassTree) {
                var cls = (ClassTree) t.getLeaf();
                return cls.getSimpleName().toString();
            }
            t = t.getParentPath();
        }
        return "";
    }

    static Optional<String> memberName(TreePath t) {
        while (t != null) {
            if (t.getLeaf() instanceof ClassTree) {
                return Optional.empty();
            } else if (t.getLeaf() instanceof MethodTree) {
                var method = (MethodTree) t.getLeaf();
                var name = method.getName().toString();
                return Optional.of(name);
            } else if (t.getLeaf() instanceof VariableTree) {
                var field = (VariableTree) t.getLeaf();
                var name = field.getName().toString();
                return Optional.of(name);
            }
            t = t.getParentPath();
        }
        return Optional.empty();
    }

    List<SymbolInformation> findSymbols(String query, int limit) {
        LOG.info(String.format("Searching for `%s`...", query));

        var result = new ArrayList<SymbolInformation>();
        var files = FileStore.all();
        var checked = 0;
        var parsed = 0;
        for (var file : files) {
            checked++;
            // First do a fast check if the query matches anything in a file
            if (!StringSearch.containsWordMatching(file, query)) continue;
            // Parse the file and check class members for matches
            LOG.info(String.format("...%s contains text matches", file.getFileName()));
            var parse = parseFile(file.toUri());
            var symbols = parse.findSymbolsMatching(query);
            parsed++;
            // If we confirm matches, add them to the results
            if (symbols.size() > 0) LOG.info(String.format("...found %d occurrences", symbols.size()));
            result.addAll(symbols);
            // If results are full, stop
            if (result.size() >= limit) break;
        }
        LOG.info(String.format("Found %d matches in %d/%d/%d files", result.size(), checked, parsed, files.size()));

        return result;
    }

    private static final Logger LOG = Logger.getLogger("main");
}
