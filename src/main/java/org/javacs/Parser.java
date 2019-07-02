package org.javacs;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.tools.*;
import org.javacs.lsp.*;

class Parser {

    // TODO merge Parser with ParseFile

    private static final JavaCompiler compiler = ServiceLoader.load(JavaCompiler.class).iterator().next();
    private static final StandardJavaFileManager fileManager =
            compiler.getStandardFileManager(__ -> {}, null, Charset.defaultCharset());

    static JavacTask parseTask(JavaFileObject file) {
        // TODO the fixed cost of creating a task is greater than the cost of parsing 1 file; eliminate the task
        // creation
        return (JavacTask)
                compiler.getTask(
                        null,
                        fileManager,
                        Parser::onError,
                        Collections.emptyList(),
                        null,
                        Collections.singletonList(file));
    }

    static JavacTask parseTask(Path source) {
        // TODO should get current contents of open files from FileStore
        JavaFileObject file =
                fileManager.getJavaFileObjectsFromFiles(Collections.singleton(source.toFile())).iterator().next();
        return parseTask(file);
    }

    static CompilationUnitTree parse(JavaFileObject file) {
        try {
            return parseTask(file).parse().iterator().next();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static CompilationUnitTree parse(Path source) {
        try {
            return parseTask(source).parse().iterator().next();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean isWordChar(char c) {
        return Character.isAlphabetic(c) || Character.isDigit(c) || c == '_' || c == '$';
    }

    private static int startOfToken(CharSequence candidate, int offset) {
        while (offset < candidate.length()) {
            char c = candidate.charAt(offset);
            if (isWordChar(c)) break;
            offset++;
        }
        return offset;
    }

    /**
     * Check if `candidate` contains all the characters of `find`, in-order, case-insensitive Matches can be
     * discontinuous if the letters of `find` match the first letters of words in `candidate` For example, fb matches
     * FooBar, but it doesn't match Foobar (exposed for testing)
     */
    static boolean matchesTitleCase(CharSequence candidate, String find) {
        int i = 0;

        tokenLoop:
        while (i < candidate.length()) {
            i = startOfToken(candidate, i);

            for (char f : find.toCharArray()) {
                // If we have reached the end of candidate without matching all of find, fail
                if (i >= candidate.length()) return false;
                // If the next character in candidate matches, advance i
                else if (Character.toLowerCase(f) == Character.toLowerCase(candidate.charAt(i))) i++;
                else {
                    // Find the start of the next word
                    while (i < candidate.length()) {
                        char c = candidate.charAt(i);
                        // If the next character is not a word, try again with the next token
                        if (!isWordChar(c)) continue tokenLoop;
                        // TODO match things like fb ~ foo_bar
                        boolean isStartOfWord = Character.isUpperCase(c);
                        boolean isMatch = Character.toLowerCase(f) == Character.toLowerCase(c);
                        if (isStartOfWord && isMatch) {
                            i++;
                            break;
                        } else i++;
                    }
                    if (i >= candidate.length()) return false;
                }
            }
            // All of find was matched!
            return true;
        }
        return false;
    }

    private static void onError(javax.tools.Diagnostic<? extends JavaFileObject> err) {
        // Too noisy, this only comes up in parse tasks which tend to be less important
        // LOG.warning(err.getMessage(Locale.getDefault()));
    }

    // TODO move all this to StringSearch
    private static final ByteBuffer SEARCH_BUFFER = ByteBuffer.allocateDirect(1 * 1024 * 1024);

    // TODO cache the progress made by searching shorter queries
    static boolean containsWordMatching(Path java, String query) {
        if (FileStore.activeDocuments().contains(java)) {
            var text = FileStore.contents(java);
            return matchesTitleCase(text, query);
        }
        try (var channel = FileChannel.open(java)) {
            // Read up to 1 MB of data from file
            var limit = Math.min((int) channel.size(), SEARCH_BUFFER.capacity());
            SEARCH_BUFFER.position(0);
            SEARCH_BUFFER.limit(limit);
            channel.read(SEARCH_BUFFER);
            SEARCH_BUFFER.position(0);
            var chars = Charset.forName("UTF-8").decode(SEARCH_BUFFER);
            return matchesTitleCase(chars, query);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static boolean containsWord(Path java, String query) {
        var search = new StringSearch(query);
        if (FileStore.activeDocuments().contains(java)) {
            var text = FileStore.contents(java).getBytes();
            return search.nextWord(text) != -1;
        }
        try (var channel = FileChannel.open(java)) {
            // Read up to 1 MB of data from file
            var limit = Math.min((int) channel.size(), SEARCH_BUFFER.capacity());
            SEARCH_BUFFER.position(0);
            SEARCH_BUFFER.limit(limit);
            channel.read(SEARCH_BUFFER);
            SEARCH_BUFFER.position(0);
            return search.nextWord(SEARCH_BUFFER) != -1;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static List<TreePath> findSymbolsMatching(CompilationUnitTree parse, String query) {
        class Find extends TreePathScanner<Void, Void> {
            List<TreePath> found = new ArrayList<>();

            void accept(TreePath path) {
                var node = path.getLeaf();
                if (node instanceof ClassTree) {
                    var c = (ClassTree) node;
                    if (matchesTitleCase(c.getSimpleName(), query)) found.add(path);
                } else if (node instanceof MethodTree) {
                    var m = (MethodTree) node;
                    if (matchesTitleCase(m.getName(), query)) found.add(path);
                } else if (node instanceof VariableTree) {
                    var v = (VariableTree) node;
                    if (matchesTitleCase(v.getName(), query)) found.add(path);
                }
            }

            @Override
            public Void visitClass(ClassTree node, Void nothing) {
                super.visitClass(node, nothing);
                accept(getCurrentPath());
                for (var t : node.getMembers()) {
                    var child = new TreePath(getCurrentPath(), t);
                    accept(child);
                }
                return null;
            }

            List<TreePath> run() {
                scan(parse, null);
                return found;
            }
        }
        return new Find().run();
    }

    static Location location(TreePath p) {
        // This is very questionable, will this Trees object actually work?
        var task = parseTask(p.getCompilationUnit().getSourceFile());
        var trees = Trees.instance(task);
        var pos = trees.getSourcePositions();
        var cu = p.getCompilationUnit();
        var lines = cu.getLineMap();
        long start = pos.getStartPosition(cu, p.getLeaf()), end = pos.getEndPosition(cu, p.getLeaf());
        int startLine = (int) lines.getLineNumber(start) - 1, startCol = (int) lines.getColumnNumber(start) - 1;
        int endLine = (int) lines.getLineNumber(end) - 1, endCol = (int) lines.getColumnNumber(end) - 1;
        var dUri = cu.getSourceFile().toUri();
        return new Location(dUri, new Range(new Position(startLine, startCol), new Position(endLine, endCol)));
    }

    static boolean containsImport(Path file, String toPackage, String toClass) {
        if (toPackage.isEmpty()) return true;
        var samePackage = Pattern.compile("^package +" + toPackage + ";");
        var importClass = Pattern.compile("^import +" + toPackage + "\\." + toClass + ";");
        var importStar = Pattern.compile("^import +" + toPackage + "\\.\\*;");
        var importStatic = Pattern.compile("^import +static +" + toPackage + "\\." + toClass);
        var startOfClass = Pattern.compile("^[\\w ]*class +\\w+");
        try (var lines = FileStore.lines(file)) {
            for (var line = lines.readLine(); line != null; line = lines.readLine()) {
                if (startOfClass.matcher(line).find()) return false;
                if (samePackage.matcher(line).find()) return true;
                if (importClass.matcher(line).find()) return true;
                if (importStar.matcher(line).find()) return true;
                if (importStatic.matcher(line).find()) return true;
                if (importClass.matcher(line).find()) return true;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return false;
    }

    static String packageName(Path file) {
        var packagePattern = Pattern.compile("^package +(.*);");
        var startOfClass = Pattern.compile("^[\\w ]*class +\\w+");
        try (var lines = FileStore.lines(file)) {
            for (var line = lines.readLine(); line != null; line = lines.readLine()) {
                if (startOfClass.matcher(line).find()) return "";
                var matchPackage = packagePattern.matcher(line);
                if (matchPackage.matches()) {
                    var id = matchPackage.group(1);
                    return id;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        // TODO fall back on parsing file
        return "";
    }

    /** Find all already-imported symbols in all .java files in workspace */
    static ExistingImports existingImports(Collection<Path> allJavaFiles) {
        var classes = new HashSet<String>();
        var packages = new HashSet<String>();
        var importClass = Pattern.compile("^import +(([\\w\\.]+)\\.\\w+);");
        var importStar = Pattern.compile("^import +([\\w\\.]+)\\.\\*;");
        var importSimple = Pattern.compile("^import +(\\w+);");
        for (var path : allJavaFiles) {
            try (var lines = FileStore.lines(path)) {
                for (var line = lines.readLine(); line != null; line = lines.readLine()) {
                    // If we reach a class declaration, stop looking for imports
                    // TODO This could be a little more specific
                    if (line.contains("class")) break;
                    // import foo.bar.Doh;
                    var matchesClass = importClass.matcher(line);
                    if (matchesClass.matches()) {
                        String className = matchesClass.group(1), packageName = matchesClass.group(2);
                        packages.add(packageName);
                        classes.add(className);
                    }
                    // import foo.bar.*
                    var matchesStar = importStar.matcher(line);
                    if (matchesStar.matches()) {
                        var packageName = matchesStar.group(1);
                        packages.add(packageName);
                    }
                    // import Doh
                    var matchesSimple = importSimple.matcher(line);
                    if (matchesSimple.matches()) {
                        var className = matchesSimple.group(1);
                        classes.add(className);
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return new ExistingImports(classes, packages);
    }

    // TODO this doesn't work for inner classes, eliminate
    static String mostName(String name) {
        var lastDot = name.lastIndexOf('.');
        return lastDot == -1 ? "" : name.substring(0, lastDot);
    }

    // TODO this doesn't work for inner classes, eliminate
    static String lastName(String name) {
        int i = name.lastIndexOf('.');
        if (i == -1) return name;
        else return name.substring(i + 1);
    }

    static String describeTree(Tree leaf) {
        if (leaf instanceof MethodTree) {
            var method = (MethodTree) leaf;
            var params = new StringJoiner(", ");
            for (var p : method.getParameters()) {
                params.add(p.getType() + " " + p.getName());
            }
            return method.getName() + "(" + params + ")";
        }
        if (leaf instanceof ClassTree) {
            var cls = (ClassTree) leaf;
            return "class " + cls.getSimpleName();
        }
        if (leaf instanceof BlockTree) {
            var block = (BlockTree) leaf;
            return String.format("{ ...%d lines... }", block.getStatements().size());
        }
        return leaf.toString();
    }

    private static Optional<String> resolveSymbol(String unresolved, ExistingImports imports, Set<String> classPath) {
        // Try to disambiguate by looking for exact matches
        // For example, Foo is exactly matched by `import com.bar.Foo`
        // Foo is *not* exactly matched by `import com.bar.*`
        var candidates = imports.classes.stream().filter(c -> c.endsWith("." + unresolved)).collect(Collectors.toSet());
        if (candidates.size() > 1) {
            LOG.warning(
                    String.format("%s is ambiguous between previously imported candidates %s", unresolved, candidates));
            return Optional.empty();
        } else if (candidates.size() == 1) {
            return Optional.of(candidates.iterator().next());
        }

        // Try to disambiguate by looking at package names
        // Both normal imports like `import com.bar.Foo`, and star-imports like `import com.bar.*`,
        // are used to generate package names
        candidates =
                classPath
                        .stream()
                        .filter(c -> lastName(c).equals(unresolved))
                        .filter(c -> imports.packages.contains(mostName(c)))
                        .collect(Collectors.toSet());
        if (candidates.size() > 1) {
            LOG.warning(String.format("%s is ambiguous between package-based candidates %s", unresolved, candidates));
            return Optional.empty();
        } else if (candidates.size() == 1) {
            return Optional.of(candidates.iterator().next());
        }

        // If there is only one class on the classpath with this name, use it
        candidates = classPath.stream().filter(c -> lastName(c).equals(unresolved)).collect(Collectors.toSet());

        if (candidates.size() > 1) {
            LOG.warning(String.format("%s is ambiguous between classpath candidates %s", unresolved, candidates));
        } else if (candidates.size() == 1) {
            return Optional.of(candidates.iterator().next());
        } else {
            LOG.warning(unresolved + " does not appear on the classpath");
        }

        // Try to import from java stdlib
        Comparator<String> order =
                Comparator.comparing(
                        c -> {
                            if (c.startsWith("java.lang")) return 1;
                            else if (c.startsWith("java.util")) return 2;
                            else if (c.startsWith("java.io")) return 3;
                            else return 4;
                        });
        return candidates.stream().filter(c -> c.startsWith("java.")).sorted(order).findFirst();
    }

    static Map<String, String> resolveSymbols(
            Set<String> unresolvedSymbols, ExistingImports imports, Set<String> classPath) {
        var result = new HashMap<String, String>();
        for (var s : unresolvedSymbols) {
            resolveSymbol(s, imports, classPath).ifPresent(resolved -> result.put(s, resolved));
        }
        return result;
    }

    static String fileName(URI uri) {
        var parts = uri.toString().split("/");
        if (parts.length == 0) return "";
        return parts[parts.length - 1];
    }

    private static final Logger LOG = Logger.getLogger("main");
}

class ExistingImports {
    /** Fully-qualified names of classes that have been imported on the source path */
    final Set<String> classes;
    /** Package names from star-imports like `import java.util.*` */
    final Set<String> packages;

    ExistingImports(Set<String> classes, Set<String> packages) {
        this.classes = classes;
        this.packages = packages;
    }
}
