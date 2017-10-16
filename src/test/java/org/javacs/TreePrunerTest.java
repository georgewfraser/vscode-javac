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
import static org.hamcrest.Matchers.equalToIgnoringWhiteSpace;
import static org.junit.Assert.assertThat;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.tools.javac.api.JavacTool;
import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.logging.Logger;
import javax.tools.JavaFileObject;
import org.junit.Test;

public class TreePrunerTest {
    private static final Logger LOG = Logger.getLogger("main");

    private JavacTask task(String source) {
        return JavacTool.create()
                .getTask(
                        null,
                        null,
                        err -> LOG.warning(err.getMessage(null)),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.singleton(
                                new StringFileObject(source, URI.create("FakePath.java"))));
    }

    @Test
    public void putSemicolonAfterCursor() throws IOException {
        String source =
                "public class Example {\n" + "  void main() {\n" + "    this.m\n" + "  }\n" + "}\n";
        JavaFileObject after =
                TreePruner.putSemicolonAfterCursor(
                        new StringFileObject(source, URI.create("Example.java")), 3, 11);

        assertThat(
                after.getCharContent(true).toString(),
                equalTo(
                        "public class Example {\n"
                                + "  void main() {\n"
                                + "    this.m;\n"
                                + "  }\n"
                                + "}\n"));
    }

    @Test
    public void putSemicolonAtEndOfLine() throws IOException {
        String source =
                "public class Example {\n"
                        + "  void main() {\n"
                        + "    this.main\n"
                        + "  }\n"
                        + "}\n";
        JavaFileObject after =
                TreePruner.putSemicolonAfterCursor(
                        new StringFileObject(source, URI.create("Example.java")), 3, 11);

        assertThat(
                after.getCharContent(true).toString(),
                equalTo(
                        "public class Example {\n"
                                + "  void main() {\n"
                                + "    this.main;\n"
                                + "  }\n"
                                + "}\n"));
    }

    @Test
    public void removeStatementsAfterCursor() throws IOException {
        String source =
                "public class Example {\n"
                        + "  void main() {\n"
                        + "    foo()\n"
                        + "    bar()\n"
                        + "    doh()\n"
                        + "  }\n"
                        + "}\n";
        JavacTask task = task(source);
        CompilationUnitTree tree = task.parse().iterator().next();

        new TreePruner(task).removeStatementsAfterCursor(tree, 4, 6);

        assertThat(
                tree.toString(),
                equalToIgnoringWhiteSpace(
                        "\n"
                                + "public class Example {\n"
                                + "    \n"
                                + "    void main() {\n"
                                + "        foo();\n"
                                + "        bar();\n"
                                + "    }\n"
                                + "}"));
    }
}
