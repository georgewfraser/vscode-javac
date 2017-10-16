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

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.sun.javadoc.MethodDoc;
import com.sun.javadoc.RootDoc;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Optional;
import org.junit.Ignore;
import org.junit.Test;

public class JavadocsTest {

    private final Javadocs docs =
            new Javadocs(
                    Collections.singleton(Paths.get("src/test/test-project/workspace/src")),
                    Collections.emptySet(),
                    __ -> Optional.empty());

    @Test
    public void findSrcZip() {
        assertTrue("Can find src.zip", Javadocs.findSrcZip().isPresent());
    }

    @Test
    public void findSystemDoc() throws IOException {
        RootDoc root = docs.index("java.util.ArrayList");

        assertThat(root.classes(), not(emptyArray()));
    }

    @Test
    public void findMethodDoc() {
        assertTrue(
                "Found method",
                docs.methodDoc(
                                "org.javacs.docs.TrickyDocstring#example(java.lang.String,java.lang.String[],java.util.List)")
                        .isPresent());
    }

    @Test
    public void findParameterizedDoc() {
        assertTrue(
                "Found method",
                docs.methodDoc("org.javacs.docs.TrickyDocstring#parameterized(java.lang.Object)")
                        .isPresent());
    }

    @Test
    @Ignore // Blocked by emptyFileManager
    public void findInheritedDoc() {
        Optional<MethodDoc> found = docs.methodDoc("org.javacs.docs.SubDoc#method()");

        assertTrue("Found method", found.isPresent());

        Optional<String> docstring = found.flatMap(Javadocs::commentText);

        assertTrue("Has inherited doc", docstring.isPresent());
        assertThat("Inherited doc is not empty", docstring.get(), not(isEmptyOrNullString()));
    }

    @Test
    @Ignore // Doesn't work yet
    public void findInterfaceDoc() {
        Optional<MethodDoc> found = docs.methodDoc("org.javacs.docs.SubDoc#interfaceMethod()");

        assertTrue("Found method", found.isPresent());

        Optional<String> docstring = found.flatMap(Javadocs::commentText);

        assertTrue("Has inherited doc", docstring.isPresent());
        assertThat("Inherited doc is not empty", docstring.get(), not(isEmptyOrNullString()));
    }
}
