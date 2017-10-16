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
import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class InferBazelConfigTest {

    private Path bazelWorkspace = Paths.get("src/test/test-project/bazel-workspace"),
            bazelTemp = Paths.get("src/test/test-project/bazel-temp");
    private InferConfig bazel =
            new InferConfig(
                    bazelWorkspace,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Paths.get("nowhere"),
                    Paths.get("nowhere"));
    private Path bazelBin = bazelWorkspace.resolve("bazel-bin"),
            bazelBinTarget =
                    bazelTemp
                            .resolve("xyz/execroot/test/bazel-out/local-fastbuild/bin")
                            .toAbsolutePath(),
            bazelGenfiles = bazelWorkspace.resolve("bazel-genfiles"),
            bazelGenfilesTarget =
                    bazelTemp
                            .resolve("xyz/execroot/test/bazel-out/local-fastbuild/genfiles")
                            .toAbsolutePath();

    @Before
    public void createBazelBinLink() throws IOException {
        assertTrue(Files.exists(bazelBinTarget));

        Files.createSymbolicLink(bazelBin, bazelBinTarget);
    }

    @After
    public void deleteBazelBinLink() throws IOException {
        Files.deleteIfExists(bazelBin);
    }

    @Before
    public void createBazelGenfilesLink() throws IOException {
        assertTrue(Files.exists(bazelGenfilesTarget));

        Files.createSymbolicLink(bazelGenfiles, bazelGenfilesTarget);
    }

    @After
    public void deleteBazelGenfilesLink() throws IOException {
        Files.deleteIfExists(bazelGenfiles);
    }

    @Test
    public void bazelWorkspaceClassPath() {
        assertThat(
                bazel.workspaceClassPath(),
                hasItem(bazelBinTarget.resolve("module/_javac/main/libmain_classes")));
    }

    @Test
    public void bazelBuildClassPath() {
        assertThat(
                bazel.buildClassPath(),
                hasItem(
                        bazelGenfilesTarget.resolve(
                                "external/com_external_external_library/jar/_ijar/jar/external/com_external_external_library/jar/external-library-1.2.jar")));
    }
}
