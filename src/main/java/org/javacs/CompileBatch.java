package org.javacs;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import javax.lang.model.util.*;
import javax.tools.*;

class CompileBatch implements AutoCloseable {
    static final int MAX_COMPLETION_ITEMS = 50;

    final JavaCompilerService parent;
    final ReusableCompiler.Borrow borrow;
    /** Indicates the task that requested the compilation is finished with it. */
    boolean closed;

    final JavacTask task;
    final Trees trees;
    final Elements elements;
    final Types types;
    final List<CompilationUnitTree> roots;

    CompileBatch(JavaCompilerService parent, Collection<? extends JavaFileObject> files) {
        this.parent = parent;
        this.borrow = batchTask(parent, files);
        this.task = borrow.task;
        this.trees = Trees.instance(borrow.task);
        this.elements = borrow.task.getElements();
        this.types = borrow.task.getTypes();
        this.roots = new ArrayList<>();
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
            if (!isValidFileRange(err)) continue;
            var className = errorText(err);
            var packageName = packageName(err);
            var location = findPackagePrivateClass(packageName, className);
            if (location != FILE_NOT_FOUND) {
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

    private static final Path FILE_NOT_FOUND = Paths.get("");

    private Path findPackagePrivateClass(String packageName, String className) {
        for (var file : FileStore.list(packageName)) {
            var parse = Parser.parseFile(file);
            for (var candidate : parse.packagePrivateClasses()) {
                if (candidate.contentEquals(className)) {
                    return file;
                }
            }
        }
        return FILE_NOT_FOUND;
    }

    @Override
    public void close() {
        closed = true;
    }

    private static ReusableCompiler.Borrow batchTask(
            JavaCompilerService parent, Collection<? extends JavaFileObject> sources) {
        parent.diags.clear();
        var options = options(parent.classPath, parent.addExports);
        return parent.compiler.getTask(parent.fileManager, parent.diags::add, options, List.of(), sources);
    }

    /** Combine source path or class path entries using the system separator, for example ':' in unix */
    private static String joinPath(Collection<Path> classOrSourcePath) {
        return classOrSourcePath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
    }

    private static List<String> options(Set<Path> classPath, Set<String> addExports) {
        var list = new ArrayList<String>();

        Collections.addAll(list, "-classpath", joinPath(classPath));
        Collections.addAll(list, "--add-modules", "ALL-MODULE-PATH");
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

    private boolean isValidFileRange(javax.tools.Diagnostic<? extends JavaFileObject> d) {
        return d.getSource().toUri().getScheme().equals("file") && d.getStartPosition() >= 0 && d.getEndPosition() >= 0;
    }
}
