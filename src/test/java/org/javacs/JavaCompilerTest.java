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

import com.google.common.collect.ImmutableList;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.tools.javac.api.JavacTool;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import org.junit.Test;

public class JavaCompilerTest {
    private static final Logger LOG = Logger.getLogger("main");

    @Test
    public void javacTool() throws IOException {
        JavaCompiler javaCompiler = JavacTool.create();
        StandardJavaFileManager fileManager =
                javaCompiler.getStandardFileManager(
                        this::reportError, null, Charset.defaultCharset());
        List<String> options =
                ImmutableList.of(
                        "-sourcepath", Paths.get("src/test/resources").toAbsolutePath().toString());
        List<String> classes = Collections.emptyList();
        File file = Paths.get("src/test/resources/org/javacs/example/Bad.java").toFile();
        Iterable<? extends JavaFileObject> compilationUnits =
                fileManager.getJavaFileObjectsFromFiles(Collections.singleton(file));
        JavacTask task =
                (JavacTask)
                        javaCompiler.getTask(
                                null,
                                fileManager,
                                this::reportError,
                                options,
                                classes,
                                compilationUnits);

        Iterable<? extends CompilationUnitTree> parsed = task.parse();
        Iterable<? extends Element> typed = task.analyze();

        LOG.info(typed.toString());
    }

    @Test
    public void javacHolder() {
        JavacHolder javac =
                JavacHolder.create(
                        Collections.singleton(Paths.get("src/test/test-project/workspace/src")),
                        Collections.emptySet());
        File file =
                Paths.get("src/test/test-project/workspace/src/org/javacs/example/Bad.java")
                        .toFile();
        DiagnosticCollector<JavaFileObject> compile =
                javac.compileBatch(Collections.singletonMap(file.toURI(), Optional.empty()));
    }

    /* It's too hard to keep this test working
    @Test
    public void incremental() {
        JavacHolder javac = JavacHolder.create(Collections.emptySet(), Collections.singleton(Paths.get("src/test/resources")), Paths.get("target/test-output"));

        // Compile Target to a .class file
        File target = Paths.get("src/test/resources/org/javacs/example/Target.java").toFile();
        DiagnosticCollector<JavaFileObject> batch = javac.compileBatch(Collections.singletonMap(target.toURI(), Optional.empty()));

        // Incremental compilation should use Target.class, not Target.java
        File dependsOnTarget = Paths.get("src/test/resources/org/javacs/example/DependsOnTarget.java").toFile();
        int[] count = {0};
        FocusedResult incremental = javac.compileFocused(dependsOnTarget.toURI(), Optional.empty(), 5, 27, true);

        assertThat(counts.get(TaskEvent.Kind.PARSE), equalTo(1));

        // Check that we can find org.javacs.example.Target
        TypeElement targetClass = incremental.task.getElements().getTypeElement("org.javacs.example.Target");

        assertThat(targetClass, not(nullValue()));
    }
    */

    private void reportError(Diagnostic<? extends JavaFileObject> error) {
        LOG.severe(error.getMessage(null));
    }
}
