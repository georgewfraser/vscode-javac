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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Optional;
import org.junit.Test;

public class SymbolIndexTest {
    private Path workspaceRoot = Paths.get("src/test/test-project/workspace");
    private SymbolIndex index =
            new SymbolIndex(workspaceRoot, () -> Collections.emptySet(), __ -> Optional.empty());

    @Test
    public void workspaceSourcePath() {
        assertThat(index.sourcePath(), contains(workspaceRoot.resolve("src").toAbsolutePath()));
    }
}
