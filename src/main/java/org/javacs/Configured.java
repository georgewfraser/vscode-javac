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

public class Configured {
    final JavacHolder compiler;
    final Javadocs docs;
    final SymbolIndex index;
    final FindSymbols find;

    Configured(JavacHolder compiler, Javadocs docs, SymbolIndex index, FindSymbols find) {
        this.compiler = compiler;
        this.docs = docs;
        this.index = index;
        this.find = find;
    }
}
