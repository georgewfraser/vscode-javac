/*
 * Original work Copyright (c) 2017 George W Fraser.
 * Modified work Copyright (c) 2017 Palantir Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 */

package org.javacs;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.file.JavacFileManager;
import com.sun.tools.javac.main.JavaCompiler;
import com.sun.tools.javac.util.Options;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.DiagnosticListener;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;

/**
 * Maintains a reference to a Java compiler, and several of its internal data structures, which we
 * need to fiddle with to get incremental compilation and extract the diagnostic information we
 * want.
 */
public class JavacHolder {

    public static JavacHolder create(Set<Path> sourcePath, Set<Path> classPath) {
        return new JavacHolder(sourcePath, classPath);
    }

    /**
     * Compile a single file, without updating the index.
     *
     * <p>As an optimization, this function may ignore code not accessible to the cursor.
     */
    public FocusedResult compileFocused(
            URI file, Optional<String> textContent, int line, int column, boolean pruneStatements) {
        JavaFileObject fileObject = findFile(file, textContent);

        if (pruneStatements)
            fileObject = TreePruner.putSemicolonAfterCursor(fileObject, line, column);

        JavacTask task = createTask(Collections.singleton(fileObject), true);
        TreePruner pruner = new TreePruner(task);

        // Record timing
        EnumMap<TaskEvent.Kind, Map<URI, Profile>> profile = new EnumMap<>(TaskEvent.Kind.class);

        task.addTaskListener(
                new TaskListener() {
                    @Override
                    public void started(TaskEvent e) {
                        if (e.getSourceFile() == null) return;

                        profile.computeIfAbsent(e.getKind(), newKind -> new HashMap<>())
                                .put(e.getSourceFile().toUri(), new Profile());
                    }

                    @Override
                    public void finished(TaskEvent e) {
                        if (e.getSourceFile() == null) return;

                        if (e.getKind() == TaskEvent.Kind.PARSE) {
                            boolean isCursorInFile =
                                    e.getCompilationUnit().getSourceFile().toUri().equals(file);

                            if (isCursorInFile) {
                                pruner.removeNonCursorMethodBodies(
                                        e.getCompilationUnit(), line, column);

                                if (pruneStatements)
                                    pruner.removeStatementsAfterCursor(
                                            e.getCompilationUnit(), line, column);
                            } else {
                                pruner.removeAllMethodBodies(e.getCompilationUnit());
                            }
                        }

                        profile.get(e.getKind()).get(e.getSourceFile().toUri()).finished =
                                Optional.of(Instant.now());
                    }
                });

        try {
            Iterable<? extends CompilationUnitTree> parse = task.parse();
            CompilationUnitTree compilationUnit = parse.iterator().next();

            try {
                Iterable<? extends Element> analyze = task.analyze();
            } catch (AssertionError e) {
                if (!catchJavacError(e)) throw e;
            }

            // Log timing
            profile.forEach(
                    (kind, timed) -> {
                        long elapsed =
                                timed.values()
                                        .stream()
                                        .mapToLong(p -> p.elapsed().toMillis())
                                        .sum();

                        if (timed.size() > 5) {
                            LOG.info(
                                    String.format(
                                            "%s\t%d ms\t%d files",
                                            kind.name(), elapsed, timed.size()));
                        } else {
                            String names =
                                    timed.keySet()
                                            .stream()
                                            .map(uri -> Paths.get(uri).getFileName().toString())
                                            .collect(Collectors.joining(", "));

                            LOG.info(String.format("%s\t%d ms\t%s", kind.name(), elapsed, names));
                        }
                    });

            Optional<TreePath> cursor = FindCursor.find(task, compilationUnit, line, column);

            return new FocusedResult(compilationUnit, cursor, task, classPathIndex);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Clear files and all their dependents, recompile, compileBatch the index, and report any
     * errors.
     *
     * <p>If these files reference un-compiled dependencies, those dependencies will also be parsed
     * and compiled.
     */
    public DiagnosticCollector<JavaFileObject> compileBatch(Map<URI, Optional<String>> files) {
        return compileBatch(files, (task, tree) -> {});
    }

    public DiagnosticCollector<JavaFileObject> compileBatch(
            Map<URI, Optional<String>> files, BiConsumer<JavacTask, CompilationUnitTree> listener) {
        return doCompile(files, listener);
    }

    private static final Logger LOG = Logger.getLogger("main");
    /** Where this javac looks for library .class files */
    public final Set<Path> classPath;
    /** Where this javac looks for .java source files */
    public final Set<Path> sourcePath;

    /**
     * Javac tool creates a new Context every time we do createTask(...), so maintaining a reference
     * to it doesn't really do anything
     */
    private final JavacTool javac = JavacTool.create();

    /*
     * JavacFileManager caches classpath internally, so both fileManager and incrementalFileManager will have the same classPath
     */

    /** Direct file manager we use to obtain reference to file we are re-compiling */
    private final JavacFileManager fileManager =
            javac.getStandardFileManager(this::onError, null, Charset.defaultCharset());

    /** File manager that hides .java files that have up-to-date sources */
    private final JavaFileManager incrementalFileManager = new IncrementalFileManager(fileManager);

    /**
     * javac isn't friendly to swapping out the error-reporting DiagnosticListener, so we install
     * this intermediate DiagnosticListener, which forwards to errorsDelegate
     */
    private void onError(Diagnostic<? extends JavaFileObject> diagnostic) {
        onErrorDelegate.report(diagnostic);
    }

    /**
     * Error reporting initially goes nowhere. We will replace this with a function that collects
     * errors so we can report all the errors associated with a file apply once.
     */
    private DiagnosticListener<JavaFileObject> onErrorDelegate = diagnostic -> {};

    /** Forward javac logging to file */
    private Writer logDelegate = createLogDelegate();

    private static Writer createLogDelegate() {
        try {
            Path output = Files.createTempFile("javac", ".log");
            BufferedWriter out = Files.newBufferedWriter(output);

            LOG.info("Forwarding javac log to " + output);

            return out;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public final ClassPathIndex classPathIndex;

    private JavacHolder(Set<Path> sourcePath, Set<Path> classPath) {
        this.sourcePath = Collections.unmodifiableSet(sourcePath);
        this.classPath = Collections.unmodifiableSet(classPath);
        this.classPathIndex = new ClassPathIndex(classPath);
    }

    static List<String> options(Set<Path> sourcePath, Set<Path> classPath) {
        return ImmutableList.of(
                "-classpath",
                Joiner.on(File.pathSeparator).join(classPath),
                "-sourcepath",
                Joiner.on(File.pathSeparator).join(sourcePath),
                "-verbose",
                // You would think we could do -Xlint:all,
                // but some lints trigger fatal errors in the presence of parse errors
                "-Xlint:cast",
                "-Xlint:deprecation",
                "-Xlint:empty",
                "-Xlint:fallthrough",
                "-Xlint:finally",
                "-Xlint:path",
                "-Xlint:unchecked",
                "-Xlint:varargs",
                "-Xlint:static");
    }

    private static class Profile {
        Instant started = Instant.now();
        Optional<Instant> finished = Optional.empty();

        Duration elapsed() {
            return Duration.between(started, finished.orElse(started));
        }
    }

    private JavacTask createTask(Collection<JavaFileObject> files, boolean incremental) {
        JavacTask result =
                javac.getTask(
                        logDelegate,
                        incrementalFileManager,
                        this::onError,
                        options(sourcePath, classPath),
                        null,
                        files);
        JavacTaskImpl impl = (JavacTaskImpl) result;

        // Better stack traces inside javac
        Options options = Options.instance(impl.getContext());

        options.put("dev", "");

        // Skip annotation processing
        JavaCompiler compiler = JavaCompiler.instance(impl.getContext());

        compiler.skipAnnotationProcessing = true;

        return result;
    }

    /** Ensure output directory exists */
    private void ensureOutputDirectory(Path dir) {
        if (!Files.exists(dir)) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw ShowMessageException.error("Error created output directory " + dir, null);
            }
        } else if (!Files.isDirectory(dir))
            throw ShowMessageException.error(
                    "Output directory " + dir + " is not a directory", null);
    }

    // TODO this should return Optional.empty() file URI is not file: and text is empty
    private JavaFileObject findFile(URI file, Optional<String> text) {
        return text.map(content -> (JavaFileObject) new StringFileObject(content, file))
                .orElseGet(() -> fileManager.getRegularFile(Paths.get(file).toFile()));
    }

    private DiagnosticCollector<JavaFileObject> startCollectingErrors() {
        DiagnosticCollector<JavaFileObject> errors = new DiagnosticCollector<>();

        onErrorDelegate =
                error -> {
                    if (error.getStartPosition() != Diagnostic.NOPOS) errors.report(error);
                    else LOG.warning("Skipped " + error.getMessage(null));
                };
        return errors;
    }

    private void stopCollectingErrors() {
        onErrorDelegate = error -> {};
    }

    private DiagnosticCollector<JavaFileObject> doCompile(
            Map<URI, Optional<String>> files, BiConsumer<JavacTask, CompilationUnitTree> forEach) {
        // TODO remove all URIs from fileManager

        List<JavaFileObject> objects =
                files.entrySet()
                        .stream()
                        .map(e -> findFile(e.getKey(), e.getValue()))
                        .collect(Collectors.toList());

        JavacTask task = createTask(objects, false);

        try {
            DiagnosticCollector<JavaFileObject> errors = startCollectingErrors();
            Iterable<? extends CompilationUnitTree> parse = task.parse();

            // TODO minimize memory use during this process
            // Instead of doing parse-all / compileFileObjects-all,
            // queue all files, then do parse / compileFileObjects on each
            // If invoked correctly, javac should avoid reparsing the same file twice
            // Then, use the same mechanism as the desugar / generate phases to remove method bodies,
            // to reclaim memory as we go

            try {
                Iterable<? extends Element> analyze = task.analyze();
            } catch (AssertionError e) {
                if (!catchJavacError(e)) throw e;
            }

            parse.forEach(tree -> forEach.accept(task, tree));

            return errors;
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            stopCollectingErrors();
        }
    }

    private boolean catchJavacError(AssertionError e) {
        if (e.getStackTrace().length > 0
                && e.getStackTrace()[0].getClassName().startsWith("com.sun.tools.javac")) {
            LOG.log(Level.WARNING, "Failed analyze phase", e);

            return true;
        } else return false;
    }
}
