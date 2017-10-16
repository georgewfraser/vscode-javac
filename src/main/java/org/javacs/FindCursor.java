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
import com.sun.source.tree.ErroneousTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.util.Optional;

class FindCursor {

    public static Optional<TreePath> find(
            JavacTask task, CompilationUnitTree source, int line, int column) {
        SourcePositions sourcePositions = Trees.instance(task).getSourcePositions();
        long offset = source.getLineMap().getPosition(line, column);

        class Finished extends RuntimeException {
            final TreePath found;

            Finished(TreePath of) {
                found = of;
            }
        }

        class Search extends TreePathScanner<Void, Void> {
            @Override
            public Void scan(Tree leaf, Void nothing) {
                if (containsCursor(leaf)) {
                    super.scan(leaf, nothing);

                    throw new Finished(new TreePath(getCurrentPath(), leaf));
                } else return null;
            }

            boolean containsCursor(Tree leaf) {
                long start = sourcePositions.getStartPosition(source, leaf);
                long end = sourcePositions.getEndPosition(source, leaf);

                return start <= offset && offset <= end;
            }

            @Override
            public Void visitErroneous(ErroneousTree node, Void nothing) {
                return super.scan(node.getErrorTrees(), nothing);
            }
        }

        try {
            new Search().scan(source, null);

            return Optional.empty();
        } catch (Finished found) {
            return Optional.of(found.found);
        }
    }
}
