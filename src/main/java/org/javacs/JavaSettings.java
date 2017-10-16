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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class JavaSettings {
    public Java java = new Java();

    public static class Java {
        public List<String> classPath = new ArrayList<>();
        public List<String> externalDependencies = new ArrayList<>();
        public Optional<String> javaHome = Optional.empty();
    }
}
