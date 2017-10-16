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

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Find java sources in test-project/workspace/src */
public class FindResource {
    public static URI uri(String resourcePath) {
        if (resourcePath.startsWith("/")) resourcePath = resourcePath.substring(1);

        Path path =
                Paths.get("./src/test/test-project/workspace/src")
                        .resolve(resourcePath)
                        .normalize();
        File file = path.toFile();

        if (!file.exists()) throw new RuntimeException(file + " does not exist");

        return file.toURI();
    }
}
