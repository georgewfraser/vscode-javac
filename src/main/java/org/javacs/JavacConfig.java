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

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

public class JavacConfig {
    public final Set<Path> classPath, workspaceClassPath, docPath;

    public JavacConfig(Set<Path> classPath, Set<Path> workspaceClassPath, Set<Path> docPath) {
        this.classPath = classPath;
        this.workspaceClassPath = workspaceClassPath;
        this.docPath = docPath;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        JavacConfig that = (JavacConfig) o;
        return Objects.equals(classPath, that.classPath)
                && Objects.equals(workspaceClassPath, that.workspaceClassPath)
                && Objects.equals(docPath, that.docPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(classPath, workspaceClassPath, docPath);
    }
}
