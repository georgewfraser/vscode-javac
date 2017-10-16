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

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.logging.Logger;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import org.junit.Ignore;
import org.junit.Test;

@Ignore
public class CompilerProfiling {
    private static final Logger LOG = Logger.getLogger("main");

    @Test
    public void parsingSpeed() throws IOException, URISyntaxException {
        URI file = FindResource.uri("/org/javacs/example/LargeFile.java");

        for (int i = 0; i < 10; i++) {
            Duration duration = compileLargeFile(file);

            LOG.info(duration.toString());
        }
    }

    private Duration compileLargeFile(URI file) {
        long start = System.nanoTime();
        JavacHolder compiler =
                JavacHolder.create(
                        Collections.singleton(Paths.get("src/test/test-project/workspace/src")),
                        Collections.emptySet());
        DiagnosticCollector<JavaFileObject> result =
                compiler.compileBatch(Collections.singletonMap(file, Optional.empty()));
        long finish = System.nanoTime();

        return Duration.ofNanos(finish - start);
    }
}
