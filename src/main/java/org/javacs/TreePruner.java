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

import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.List;
import java.io.IOException;
import java.io.Reader;
import javax.tools.JavaFileObject;

/** Fix up the tree to make it easier to autocomplete, index */
class TreePruner {
    private final JavacTask task;

    TreePruner(JavacTask task) {
        this.task = task;
    }

    void removeNonCursorMethodBodies(CompilationUnitTree source, int line, int column) {
        long offset = source.getLineMap().getPosition(line, column);

        doRemoveNonCursorMethodBodies(source, offset);
    }

    void removeAllMethodBodies(CompilationUnitTree source) {
        doRemoveNonCursorMethodBodies(source, -1);
    }

    private void doRemoveNonCursorMethodBodies(CompilationUnitTree source, long offset) {
        SourcePositions sourcePositions = Trees.instance(task).getSourcePositions();

        class Pruner extends TreeScanner<Void, Void> {
            @Override
            public Void visitBlock(BlockTree node, Void aVoid) {
                if (containsCursor(node)) super.visitBlock(node, aVoid);
                else {
                    JCTree.JCBlock impl = (JCTree.JCBlock) node;

                    impl.stats = List.nil();
                }

                return null;
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

        new Pruner().scan(source, null);
    }

    /** Remove all statements after the statement the cursor is in */
    void removeStatementsAfterCursor(CompilationUnitTree source, int line, int column) {
        SourcePositions sourcePositions = Trees.instance(task).getSourcePositions();
        long offset = source.getLineMap().getPosition(line, column);

        class Pruner extends TreeScanner<Void, Void> {
            @Override
            public Void visitBlock(BlockTree node, Void aVoid) {
                JCTree.JCBlock impl = (JCTree.JCBlock) node;

                impl.stats = pruneStatements(impl.stats);

                return super.visitBlock(node, aVoid);
            }

            @Override
            public Void visitSwitch(SwitchTree node, Void aVoid) {
                JCTree.JCSwitch impl = (JCTree.JCSwitch) node;

                impl.cases = pruneStatements(impl.cases);

                return super.visitSwitch(node, aVoid);
            }

            @Override
            public Void visitCase(CaseTree node, Void aVoid) {
                JCTree.JCCase impl = (JCTree.JCCase) node;

                impl.stats = pruneStatements(impl.stats);

                return super.visitCase(node, aVoid);
            }

            private <T extends Tree> List<T> pruneStatements(List<T> stats) {
                int countStatements = 0;
                boolean foundCursor = false;

                // Scan up to statement containing cursor
                while (countStatements < stats.size()) {
                    T s = stats.get(countStatements);

                    if (containsCursor(s)) foundCursor = true;
                    else if (foundCursor) break;
                    else this.scan(s, null);

                    countStatements++;
                }

                // Remove all statements after statement containing cursor
                return stats.take(countStatements);
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

        new Pruner().scan(source, null);
    }

    /**
     * Insert ';' after the users cursor so we recover from parse errors in a helpful way when doing
     * autocomplete.
     */
    static JavaFileObject putSemicolonAfterCursor(
            JavaFileObject file, int cursorLine, int cursorCharacter) {
        try (Reader reader = file.openReader(true)) {
            StringBuilder acc = new StringBuilder();
            int line = 1, character = 1;

            // Go to the cursor
            while (line < cursorLine || character < cursorCharacter) {
                int next = reader.read();

                if (next == -1)
                    throw new RuntimeException(
                            "End of file "
                                    + file
                                    + " before cursor "
                                    + cursorLine
                                    + ":"
                                    + cursorCharacter);
                else if (next == '\n') {
                    line++;
                    character = 1;
                } else character++;

                acc.append((char) next);
            }

            // Go to the end of the line
            while (true) {
                int next = reader.read();

                if (next == -1 || next == '\n') break;

                acc.append((char) next);
            }

            acc.append(";\n");

            for (int next = reader.read(); next > 0; next = reader.read()) {
                acc.append((char) next);
            }

            return new StringFileObject(acc.toString(), file.toUri());
        } catch (IOException e) {
            throw ShowMessageException.error("Error reading " + file, e);
        }
    }
}
