package org.javacs_server;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.*;
import org.javacs.lsp.Location;
import org.javacs.lsp.Position;
import org.javacs.lsp.Range;
import org.javacs.lsp.SymbolInformation;
import org.javacs.lsp.SymbolKind;

class Parser {

  // TODO merge Parser with ParseFile

  private static final JavaCompiler compiler = ServiceLoader.load(JavaCompiler.class).iterator()
      .next();
  private static final StandardJavaFileManager fileManager =
      compiler.getStandardFileManager(__ -> {
      }, null, Charset.defaultCharset());

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
    JavaFileObject file =
        fileManager.getJavaFileObjectsFromFiles(Collections.singleton(source.toFile())).iterator()
            .next();
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

  /**
   * Check if `candidate` contains all the characters of `find`, in-order, case-insensitive Matches
   * can be discontinuous if the letters of `find` match the first letters of words in `candidate`
   * For example, fb matches FooBar, but it doesn't match Foobar (exposed for testing)
   */
  static boolean matchesTitleCase(CharSequence candidate, String find) {
    int i = 0;
    for (char f : find.toCharArray()) {
      // If we have reached the end of candidate without matching all of find, fail
      if (i >= candidate.length()) {
        return false;
      }
        // If the next character in candidate matches, advance i
      else if (Character.toLowerCase(f) == Character.toLowerCase(candidate.charAt(i))) {
        i++;
      } else {
        // Find the start of the next word
        while (i < candidate.length()) {
          char c = candidate.charAt(i);
          boolean isStartOfWord = Character.isUpperCase(c);
          boolean isMatch = Character.toLowerCase(f) == Character.toLowerCase(c);
          if (isStartOfWord && isMatch) {
            i++;
            break;
          } else {
            i++;
          }
        }
        if (i == candidate.length()) {
          return false;
        }
      }
    }
    return true;
  }

  private static void onError(javax.tools.Diagnostic<? extends JavaFileObject> err) {
    // Too noisy, this only comes up in parse tasks which tend to be less important
    // LOG.warning(err.getMessage(Locale.getDefault()));
  }

  private static final Pattern WORD = Pattern.compile("\\b\\w+\\b");

  static boolean containsWordMatching(Path java, String query) {
    try {
      for (var line : Files.readAllLines(java)) {
        var pattern = WORD.matcher(line);
        while (pattern.find()) {
          var word = pattern.group(0);
          if (matchesTitleCase(word, query)) {
            return true;
          }
        }
      }
      return false;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static Stream<TreePath> findSymbolsMatching(CompilationUnitTree parse, String query) {
    class Find extends TreePathScanner<Void, Void> {

      List<TreePath> found = new ArrayList<>();

      void accept(TreePath path) {
        var node = path.getLeaf();
        if (node instanceof ClassTree) {
          var c = (ClassTree) node;
          if (matchesTitleCase(c.getSimpleName(), query)) {
            found.add(path);
          }
        } else if (node instanceof MethodTree) {
          var m = (MethodTree) node;
          if (matchesTitleCase(m.getName(), query)) {
            found.add(path);
          }
        } else if (node instanceof VariableTree) {
          var v = (VariableTree) node;
          if (matchesTitleCase(v.getName(), query)) {
            found.add(path);
          }
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
    return new Find().run().stream();
  }

  /**
   * Search `dir` for .java files containing important symbols matching `query`
   */
  static Stream<TreePath> findSymbols(Path dir, String query) {
    if (!Files.exists(dir)) {
      LOG.warning(dir + " does not exist");
      return Stream.empty();
    }

    var match = FileSystems.getDefault().getPathMatcher("glob:*.java");

    try {
      return Files.walk(dir)
          .filter(java -> match.matches(java.getFileName()))
          .filter(java -> containsWordMatching(java, query))
          .flatMap(java -> findSymbolsMatching(parse(java), query));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  static List<TreePath> documentSymbols(Path java, String content) {
    var parse = parse(new StringFileObject(content, java.toUri()));
    return findSymbolsMatching(parse, "").collect(Collectors.toList());
  }

  static Location location(TreePath p) {
    // This is very questionable, will this Trees object actually work?
    var task = parseTask(p.getCompilationUnit().getSourceFile());
    var trees = Trees.instance(task);
    var pos = trees.getSourcePositions();
    var cu = p.getCompilationUnit();
    var lines = cu.getLineMap();
    long start = pos.getStartPosition(cu, p.getLeaf()), end = pos.getEndPosition(cu, p.getLeaf());
    int startLine = (int) lines.getLineNumber(start) - 1, startCol =
        (int) lines.getColumnNumber(start) - 1;
    int endLine = (int) lines.getLineNumber(end) - 1, endCol = (int) lines.getColumnNumber(end) - 1;
    var dUri = cu.getSourceFile().toUri();
    return new Location(dUri,
        new Range(new Position(startLine, startCol), new Position(endLine, endCol)));
  }

  private static Integer asSymbolKind(Tree.Kind k) {
    switch (k) {
      case ANNOTATION_TYPE:
      case CLASS:
        return SymbolKind.Class;
      case ENUM:
        return SymbolKind.Enum;
      case INTERFACE:
        return SymbolKind.Interface;
      case METHOD:
        return SymbolKind.Method;
      case TYPE_PARAMETER:
        return SymbolKind.TypeParameter;
      case VARIABLE:
        // This method is used for symbol-search functionality,
        // where we only return fields, not local variables
        return SymbolKind.Field;
      default:
        return null;
    }
  }

  private static String containerName(TreePath path) {
    var parent = path.getParentPath();
    while (parent != null) {
      var t = parent.getLeaf();
      if (t instanceof ClassTree) {
        var c = (ClassTree) t;
        return c.getSimpleName().toString();
      } else if (t instanceof CompilationUnitTree) {
        var c = (CompilationUnitTree) t;
        return Objects.toString(c.getPackageName(), "");
      } else {
        parent = parent.getParentPath();
      }
    }
    return null;
  }

  private static String symbolName(Tree t) {
    if (t instanceof ClassTree) {
      var c = (ClassTree) t;
      return c.getSimpleName().toString();
    } else if (t instanceof MethodTree) {
      var m = (MethodTree) t;
      return m.getName().toString();
    } else if (t instanceof VariableTree) {
      var v = (VariableTree) t;
      return v.getName().toString();
    } else {
      LOG.warning("Don't know how to create SymbolInformation from " + t);
      return "???";
    }
  }

  static SymbolInformation asSymbolInformation(TreePath path) {
    var i = new SymbolInformation();
    var t = path.getLeaf();
    i.kind = asSymbolKind(t.getKind());
    i.name = symbolName(t);
    i.containerName = containerName(path);
    i.location = Parser.location(path);
    return i;
  }

  /**
   * Find all already-imported symbols in all .java files in sourcePath
   */
  static ExistingImports existingImports(Collection<Path> sourcePath) {
    var classes = new HashSet<String>();
    var packages = new HashSet<String>();
    var importClass = Pattern.compile("^import +(([\\w\\.]+)\\.\\w+);");
    var importStar = Pattern.compile("^import +([\\w\\.]+)\\.\\*;");
    var importSimple = Pattern.compile("^import +(\\w+);");
    Consumer<Path> findImports =
        path -> {
          try (var lines = Files.newBufferedReader(path)) {
            while (true) {
              var line = lines.readLine();
              // If we reach the end of the file, stop looking for imports
              if (line == null) {
                return;
              }
              // If we reach a class declaration, stop looking for imports
              // TODO This could be a little more specific
              if (line.contains("class")) {
                return;
              }
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
        };
    sourcePath.stream().flatMap(InferSourcePath::allJavaFiles).forEach(findImports);
    return new ExistingImports(classes, packages);
  }

  static String mostName(String name) {
    var lastDot = name.lastIndexOf('.');
    return lastDot == -1 ? "" : name.substring(0, lastDot);
  }

  static String lastName(String name) {
    int i = name.lastIndexOf('.');
    if (i == -1) {
      return name;
    } else {
      return name.substring(i + 1);
    }
  }

  // TODO does this really belong in Parser?
  private static Optional<String> resolveSymbol(String unresolved, ExistingImports imports,
      Set<String> classPath) {
    // Try to disambiguate by looking for exact matches
    // For example, Foo is exactly matched by `import com.bar.Foo`
    // Foo is *not* exactly matched by `import com.bar.*`
    var candidates = imports.classes.stream().filter(c -> c.endsWith("." + unresolved))
        .collect(Collectors.toSet());
    if (candidates.size() > 1) {
      LOG.warning(
          String.format("%s is ambiguous between previously imported candidates %s", unresolved,
              candidates));
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
      LOG.warning(String
          .format("%s is ambiguous between package-based candidates %s", unresolved, candidates));
      return Optional.empty();
    } else if (candidates.size() == 1) {
      return Optional.of(candidates.iterator().next());
    }

    // If there is only one class on the classpath with this name, use it
    candidates = classPath.stream().filter(c -> lastName(c).equals(unresolved))
        .collect(Collectors.toSet());

    if (candidates.size() > 1) {
      LOG.warning(
          String.format("%s is ambiguous between classpath candidates %s", unresolved, candidates));
    } else if (candidates.size() == 1) {
      return Optional.of(candidates.iterator().next());
    } else {
      LOG.warning(unresolved + " does not appear on the classpath");
    }

    // Try to import from java stdlib
    Comparator<String> order =
        Comparator.comparing(
            c -> {
              if (c.startsWith("java.lang")) {
                return 1;
              } else if (c.startsWith("java.util")) {
                return 2;
              } else if (c.startsWith("java.io")) {
                return 3;
              } else {
                return 4;
              }
            });
    return candidates.stream().filter(c -> c.startsWith("java.")).sorted(order).findFirst();
  }

  // TODO does this really belong in Parser?
  static Map<String, String> resolveSymbols(
      Set<String> unresolvedSymbols, ExistingImports imports, Set<String> classPath) {
    var result = new HashMap<String, String>();
    for (var s : unresolvedSymbols) {
      resolveSymbol(s, imports, classPath).ifPresent(resolved -> result.put(s, resolved));
    }
    return result;
  }

  static String fileName(URI uri) {
    var parts = uri.getPath().split(File.separator);
    if (parts.length == 0) {
      return "";
    }
    return parts[parts.length - 1];
  }

  private static final Logger LOG = Logger.getLogger("main");
}
