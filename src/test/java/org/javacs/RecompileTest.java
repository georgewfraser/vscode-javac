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

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Optional;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import org.junit.Test;

public class RecompileTest {
    @Test
    public void compileTwice() {
        URI file = FindResource.uri("/org/javacs/example/CompileTwice.java");
        JavacHolder compiler = newCompiler();
        DiagnosticCollector<JavaFileObject> compile =
                compiler.compileBatch(Collections.singletonMap(file, Optional.empty()));

        assertThat(compile.getDiagnostics(), empty());

        // Compile again
        compile = compiler.compileBatch(Collections.singletonMap(file, Optional.empty()));

        assertThat(compile.getDiagnostics(), empty());
    }

    @Test
    public void fixParseError() {
        URI bad = FindResource.uri("/org/javacs/example/FixParseErrorBefore.java");
        URI good = FindResource.uri("/org/javacs/example/FixParseErrorAfter.java");
        JavacHolder compiler = newCompiler();
        DiagnosticCollector<JavaFileObject> badErrors =
                compiler.compileBatch(Collections.singletonMap(bad, Optional.empty()));

        assertThat(badErrors.getDiagnostics(), not(empty()));

        // Parse again
        DiagnosticCollector<JavaFileObject> goodErrors =
                compiler.compileBatch(Collections.singletonMap(good, Optional.empty()));

        assertThat(goodErrors.getDiagnostics(), empty());
    }

    @Test
    public void fixTypeError() {
        URI bad = FindResource.uri("/org/javacs/example/FixTypeErrorBefore.java");
        URI good = FindResource.uri("/org/javacs/example/FixTypeErrorAfter.java");
        JavacHolder compiler = newCompiler();
        DiagnosticCollector<JavaFileObject> badErrors =
                compiler.compileBatch(Collections.singletonMap(bad, Optional.empty()));

        assertThat(badErrors.getDiagnostics(), not(empty()));

        // Parse again
        DiagnosticCollector<JavaFileObject> goodErrors =
                compiler.compileBatch(Collections.singletonMap(good, Optional.empty()));

        assertThat(goodErrors.getDiagnostics(), empty());
    }

    private static JavacHolder newCompiler() {
        return JavacHolder.create(
                Collections.singleton(Paths.get("src/test/test-project/workspace/src")),
                Collections.emptySet());
    }

    @Test
    public void keepTypeError() throws IOException {
        URI file = FindResource.uri("/org/javacs/example/UndefinedSymbol.java");
        JavacHolder compiler = newCompiler();

        // Compile once
        DiagnosticCollector<JavaFileObject> errors =
                compiler.compileBatch(Collections.singletonMap(file, Optional.empty()));
        assertThat(errors.getDiagnostics(), not(empty()));

        // Compile twice
        errors = compiler.compileBatch(Collections.singletonMap(file, Optional.empty()));

        assertThat(errors.getDiagnostics(), not(empty()));
    }
}
