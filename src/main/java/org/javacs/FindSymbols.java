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
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.net.URI;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.*;
import javax.tools.Diagnostic;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

class FindSymbols {
    private final SymbolIndex index;
    private final JavacHolder compiler;
    private final Function<URI, Optional<String>> activeContent;

    FindSymbols(
            SymbolIndex index,
            JavacHolder compiler,
            Function<URI, Optional<String>> activeContent) {
        this.index = index;
        this.compiler = compiler;
        this.activeContent = activeContent;
    }

    /**
     * Find a symbol in its file.
     *
     * <p>It's possible that `symbol` comes from a .class file where the corresponding .java file
     * was not visited during incremental compilation. In order to be sure we have access to the
     * source positions, we will recompile the .java file where `symbol` was declared.
     */
    Optional<Location> find(Element symbol) {
        index.updateOpenFiles();

        return findFile(symbol).flatMap(file -> findIn(symbol, file));
    }

    /** Find all references to a symbol */
    Stream<Location> references(Element symbol) {
        String name = symbol.getSimpleName().toString();

        return findReferences(index.potentialReferences(name), symbol).stream();
    }

    private void visitElements(URI source, BiConsumer<JavacTask, Element> forEach) {
        Map<URI, Optional<String>> todo =
                Collections.singletonMap(source, activeContent.apply(source));

        compiler.compileBatch(
                todo,
                (task, compilationUnit) -> {
                    Trees trees = Trees.instance(task);

                    new TreePathScanner<Void, Void>() {
                        @Override
                        public Void visitClass(ClassTree node, Void aVoid) {
                            addDeclaration();

                            return super.visitClass(node, aVoid);
                        }

                        @Override
                        public Void visitMethod(MethodTree node, Void aVoid) {
                            addDeclaration();

                            return super.visitMethod(node, aVoid);
                        }

                        @Override
                        public Void visitVariable(VariableTree node, Void aVoid) {
                            addDeclaration();

                            return super.visitVariable(node, aVoid);
                        }

                        private void addDeclaration() {
                            Element el = trees.getElement(getCurrentPath());

                            forEach.accept(task, el);
                        }
                    }.scan(compilationUnit, null);
                });
    }

    private Optional<Location> findIn(Element symbol, URI file) {
        List<Location> result = new ArrayList<>();

        visitElements(
                file,
                (task, found) -> {
                    if (sameSymbol(symbol, found)) {
                        findElementName(found, Trees.instance(task)).ifPresent(result::add);
                    }
                });

        if (!result.isEmpty()) return Optional.of(result.get(0));
        else return Optional.empty();
    }

    private List<Location> findReferences(Collection<URI> files, Element target) {
        List<Location> found = new ArrayList<>();
        Map<URI, Optional<String>> todo =
                files.stream().collect(Collectors.toMap(uri -> uri, activeContent));

        compiler.compileBatch(
                todo,
                (task, compilationUnit) -> {
                    Trees trees = Trees.instance(task);

                    new TreePathScanner<Void, Void>() {
                        @Override
                        public Void visitMemberSelect(MemberSelectTree node, Void aVoid) {
                            addReference();

                            return super.visitMemberSelect(node, aVoid);
                        }

                        @Override
                        public Void visitMemberReference(MemberReferenceTree node, Void aVoid) {
                            addReference();

                            return super.visitMemberReference(node, aVoid);
                        }

                        @Override
                        public Void visitNewClass(NewClassTree node, Void aVoid) {
                            addReference();

                            return super.visitNewClass(node, aVoid);
                        }

                        @Override
                        public Void visitIdentifier(IdentifierTree node, Void aVoid) {
                            addReference();

                            return super.visitIdentifier(node, aVoid);
                        }

                        private void addReference() {
                            Element symbol = trees.getElement(getCurrentPath());

                            if (sameSymbol(target, symbol))
                                findPath(getCurrentPath(), trees).ifPresent(found::add);
                        }
                    }.scan(compilationUnit, null);
                });

        return found;
    }

    private static Optional<Location> findPath(TreePath path, Trees trees) {
        CompilationUnitTree compilationUnit = path.getCompilationUnit();
        long start = trees.getSourcePositions().getStartPosition(compilationUnit, path.getLeaf());
        long end = trees.getSourcePositions().getEndPosition(compilationUnit, path.getLeaf());

        if (start == Diagnostic.NOPOS) return Optional.empty();

        if (end == Diagnostic.NOPOS) end = start;

        int startLine = (int) compilationUnit.getLineMap().getLineNumber(start);
        int startColumn = (int) compilationUnit.getLineMap().getColumnNumber(start);
        int endLine = (int) compilationUnit.getLineMap().getLineNumber(end);
        int endColumn = (int) compilationUnit.getLineMap().getColumnNumber(end);

        return Optional.of(
                new Location(
                        compilationUnit.getSourceFile().toUri().toString(),
                        new Range(
                                new Position(startLine - 1, startColumn - 1),
                                new Position(endLine - 1, endColumn - 1))));
    }

    private Optional<URI> findFile(Element symbol) {
        return topLevelClass(symbol).flatMap(index::findDeclaringFile);
    }

    private Optional<TypeElement> topLevelClass(Element symbol) {
        TypeElement result = null;

        while (symbol != null) {
            if (symbol instanceof TypeElement) result = (TypeElement) symbol;

            symbol = symbol.getEnclosingElement();
        }

        return Optional.ofNullable(result);
    }

    private static String qualifiedName(Element s) {
        StringJoiner acc = new StringJoiner(".");

        createQualifiedName(s, acc);

        return acc.toString();
    }

    private static void createQualifiedName(Element s, StringJoiner acc) {
        if (s != null) {
            createQualifiedName(s.getEnclosingElement(), acc);

            if (s instanceof PackageElement)
                acc.add(((PackageElement) s).getQualifiedName().toString());
            else if (s.getSimpleName().length() != 0) acc.add(s.getSimpleName().toString());
        }
    }

    /** Find a more accurate position for symbol by searching for its name. */
    private static Optional<Location> findElementName(Element symbol, Trees trees) {
        TreePath path = trees.getPath(symbol);
        Name name =
                symbol.getKind() == ElementKind.CONSTRUCTOR
                        ? symbol.getEnclosingElement().getSimpleName()
                        : symbol.getSimpleName();

        return SymbolIndex.findTreeName(name, path, trees);
    }

    private static boolean sameSymbol(Element target, Element symbol) {
        return symbol != null
                && target != null
                && toStringEquals(symbol.getEnclosingElement(), target.getEnclosingElement())
                && toStringEquals(symbol, target);
    }

    private static boolean toStringEquals(Object left, Object right) {
        return Objects.equals(Objects.toString(left, ""), Objects.toString(right, ""));
    }

    private static boolean shouldIndex(Element symbol) {
        if (symbol == null) return false;

        ElementKind kind = symbol.getKind();

        switch (kind) {
            case ENUM:
            case ANNOTATION_TYPE:
            case INTERFACE:
            case ENUM_CONSTANT:
            case FIELD:
            case METHOD:
                return true;
            case CLASS:
                return !isAnonymous(symbol);
            case CONSTRUCTOR:
                // TODO also skip generated constructors
                return !isAnonymous(symbol.getEnclosingElement());
            default:
                return false;
        }
    }

    private static boolean isAnonymous(Element symbol) {
        return symbol.getSimpleName().toString().isEmpty();
    }
}
