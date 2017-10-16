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

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

public class SourceFileIndex {
    String packageName = "";

    /** Simple names of classes declared in this file */
    final Set<ReachableClass> topLevelClasses = new HashSet<>();

    /** Simple name of declarations in this file, including classes, methods, and fields */
    final Set<String> declarations = new HashSet<>();

    /**
     * Simple names of reference appearing in this file.
     *
     * <p>This is fast to compute and provides a useful starting point for find-references
     * operations.
     */
    final Set<String> references = new HashSet<>();

    final Instant updated = Instant.now();
}
