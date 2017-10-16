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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import com.google.common.collect.ImmutableList;
import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.file.JavacFileManager;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Paths;
import javax.tools.StandardLocation;
import org.javacs.pubapi.PubApi;
import org.junit.Before;
import org.junit.Test;

public class IncrementalFileManagerTest {
    private JavacFileManager delegate =
            JavacTool.create().getStandardFileManager(__ -> {}, null, Charset.defaultCharset());
    private IncrementalFileManager test = new IncrementalFileManager(delegate);
    private File sourcePath = Paths.get("./src/test/test-project/workspace/src").toFile();
    private File classPath = Paths.get("./src/test/test-project/workspace/target/classes").toFile();

    @Before
    public void setPaths() throws IOException {
        delegate.setLocation(StandardLocation.SOURCE_PATH, ImmutableList.of(sourcePath));
        delegate.setLocation(StandardLocation.CLASS_PATH, ImmutableList.of(classPath));
    }

    @Test
    public void sourceFileSignature() {
        PubApi sig = test.sourceSignature("com.example.Signatures").get();

        assertThat(
                sig.types.get("com.example.Signatures").pubApi.methods.keySet(),
                hasItems("void voidMethod()", "java.lang.String stringMethod()"));
        assertThat(
                sig.types.get("com.example.Signatures").pubApi.methods.keySet(),
                not(hasItems("void privateMethod()")));
        assertThat(
                sig.types.get("com.example.Signatures").pubApi.types.keySet(),
                hasItems(
                        "com.example.Signatures$RegularInnerClass",
                        "com.example.Signatures$StaticInnerClass"));
    }

    @Test
    public void classFileSignature() {
        PubApi sig = test.classSignature("com.example.Signatures").get();

        assertThat(
                sig.types.get("com.example.Signatures").pubApi.methods.keySet(),
                hasItems("void voidMethod()", "java.lang.String stringMethod()"));
        assertThat(
                sig.types.get("com.example.Signatures").pubApi.methods.keySet(),
                not(hasItems("void privateMethod()")));
        assertThat(
                sig.types.get("com.example.Signatures").pubApi.types.keySet(),
                hasItems(
                        "com.example.Signatures$RegularInnerClass",
                        "com.example.Signatures$StaticInnerClass"));
    }

    @Test
    public void simpleSignatureEquals() {
        PubApi classSig = test.classSignature("com.example.Signatures").get(),
                sourceSig = test.sourceSignature("com.example.Signatures").get();

        assertThat(classSig, equalTo(sourceSig));
    }

    @Test
    public void packagePrivateSourceSignature() {
        PubApi sig = test.sourceSignature("com.example.PackagePrivate").get();

        assertThat(
                sig.types.get("com.example.PackagePrivate").pubApi.methods.keySet(),
                hasItem("void packagePrivateMethod()"));
    }

    @Test
    public void packagePrivateClassSignature() {
        PubApi sig = test.classSignature("com.example.PackagePrivate").get();

        assertThat(
                sig.types.get("com.example.PackagePrivate").pubApi.methods.keySet(),
                hasItem("void packagePrivateMethod()"));
    }

    @Test
    public void packagePrivateEquals() {
        PubApi classSig = test.classSignature("com.example.PackagePrivate").get(),
                sourceSig = test.sourceSignature("com.example.PackagePrivate").get();

        assertThat(classSig, equalTo(sourceSig));
    }
}
