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

import com.google.common.collect.Lists;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;
import com.sun.tools.javac.file.JavacFileManager;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.Pretty;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Names;
import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.tools.JavaFileObject;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;

class RefactorFile {
    private final JavacTask task;
    private final CompilationUnitTree source;
    private final SourcePositions pos;
    private static final Position zero = new Position(0, 0);

    RefactorFile(JavacTask task, CompilationUnitTree source) {
        this.task = task;
        this.source = source;
        this.pos = Trees.instance(task).getSourcePositions();
    }

    List<TextEdit> addImport(String packageName, String className) {
        Objects.requireNonNull(packageName, "Package name is null");
        Objects.requireNonNull(className, "Class name is null");

        if (alreadyImported(source, packageName, className)) return Collections.emptyList();

        return Collections.singletonList(insertSomehow(packageName, className));
    }

    private TextEdit insertSomehow(String packageName, String className) {
        Optional<TextEdit> done = insertInAlphabeticalOrder(packageName, className);
        if (done.isPresent()) return done.get();

        done = insertAfterImports(packageName, className);
        if (done.isPresent()) return done.get();

        done = insertAfterPackage(packageName, className);
        if (done.isPresent()) return done.get();

        return insertAtTop(packageName, className);
    }

    private Optional<TextEdit> insertInAlphabeticalOrder(String packageName, String className) {
        String insertLine = String.format("import %s.%s;\n", packageName, className);

        return source.getImports()
                .stream()
                .filter(i -> qualifiedName(i).compareTo(packageName + "." + className) > 0)
                .map(this::startPosition)
                .findFirst()
                .map(at -> new TextEdit(new Range(at, at), insertLine));
    }

    private String qualifiedName(ImportTree tree) {
        return tree.getQualifiedIdentifier().toString();
    }

    private Optional<TextEdit> insertAfterImports(String packageName, String className) {
        String insertLine = String.format("\nimport %s.%s;", packageName, className);

        return endOfImports().map(at -> new TextEdit(new Range(at, at), insertLine));
    }

    private Optional<TextEdit> insertAfterPackage(String packageName, String className) {
        String insertLine = String.format("\n\nimport %s.%s;", packageName, className);

        return endOfPackage().map(at -> new TextEdit(new Range(at, at), insertLine));
    }

    private TextEdit insertAtTop(String packageName, String className) {
        String insertLine = String.format("import %s.%s;\n\n", packageName, className);

        return new TextEdit(new Range(zero, zero), insertLine);
    }

    private Optional<Position> endOfImports() {
        return source.getImports().stream().max(this::comparePosition).map(this::endPosition);
    }

    private Optional<Position> endOfPackage() {
        return Optional.ofNullable(source.getPackageName()).map(this::endPosition);
    }

    private Position startPosition(Tree tree) {
        return findOffset(pos.getStartPosition(source, tree), false);
    }

    private Position endPosition(Tree tree) {
        return findOffset(pos.getEndPosition(source, tree), true);
    }

    /** Convert on offset-based position to a {@link Position} */
    private Position findOffset(long find, boolean endOfLine) {
        JavaFileObject file = source.getSourceFile();

        try (Reader in = file.openReader(true)) {
            long offset = 0;
            int line = 0;
            int character = 0;

            while (offset < find) {
                int next = in.read();

                if (next < 0) break;
                else {
                    offset++;
                    character++;

                    if (next == '\n') {
                        line++;
                        character = 0;
                    }
                }
            }

            if (endOfLine) {
                while (true) {
                    int next = in.read();

                    if (next < 0 || next == '\n') break;
                    else {
                        offset++;
                        character++;
                    }
                }
            }

            return new Position(line, character);
        } catch (IOException e) {
            throw ShowMessageException.error(e.getMessage(), e);
        }
    }

    private static boolean alreadyImported(
            CompilationUnitTree source, String packageName, String className) {
        return source.getImports().stream().anyMatch(i -> importEquals(i, packageName, className));
    }

    private static String organizeImports(
            CompilationUnitTree source, String packageName, String className) {
        Context context = new Context();
        JavacFileManager fileManager = new JavacFileManager(context, true, null);
        Names names = Names.instance(context);
        TreeMaker factory = TreeMaker.instance(context);
        List<ImportTree> imports = Lists.newArrayList(source.getImports());

        imports.add(
                factory.Import(
                        factory.Select(
                                factory.Ident(names.fromString(packageName)),
                                names.fromString(className)),
                        false));

        JCTree.JCCompilationUnit after =
                factory.TopLevel(
                        list(JCTree.JCAnnotation.class, source.getPackageAnnotations()),
                        (JCTree.JCExpression) source.getPackageName(),
                        list(JCTree.class, imports));
        StringWriter buffer = new StringWriter();
        Pretty prettyPrint = new Pretty(buffer, true);

        try {
            prettyPrint.printUnit(after, null);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return buffer.toString();
    }

    private static <T> com.sun.tools.javac.util.List<T> list(Class<T> cast, List<?> from) {
        List<T> out = new ArrayList<>();

        for (Object each : from) {
            out.add(cast.cast(each));
        }

        return com.sun.tools.javac.util.List.from(out);
    }

    private int comparePosition(Tree left, Tree right) {
        long leftPos = pos.getStartPosition(source, left);
        long rightPos = pos.getStartPosition(source, right);

        return Long.compare(leftPos, rightPos);
    }

    private static boolean importEquals(ImportTree i, String packageName, String className) {
        MemberSelectTree access = (MemberSelectTree) i.getQualifiedIdentifier();

        return access.getExpression().toString().equals(packageName)
                && access.getIdentifier().toString().equals(className);
    }
}
