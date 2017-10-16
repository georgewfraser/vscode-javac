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

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import java.util.Optional;

public class FocusedResult {
    final CompilationUnitTree compilationUnit;
    final Optional<TreePath> cursor;
    final JavacTask task;
    final ClassPathIndex classPath;

    FocusedResult(
            CompilationUnitTree compilationUnit,
            Optional<TreePath> cursor,
            JavacTask task,
            ClassPathIndex classPath) {
        this.compilationUnit = compilationUnit;
        this.cursor = cursor;
        this.task = task;
        this.classPath = classPath;
    }
}
