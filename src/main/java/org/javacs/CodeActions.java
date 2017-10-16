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

import com.google.common.collect.ImmutableList;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;

class CodeActions {
    private final JavacHolder compiler;
    private final URI file;
    private final Optional<String> textContent;
    private final JavacTask task;
    private final CompilationUnitTree source;
    private final SymbolIndex index;

    CodeActions(
            JavacHolder compiler,
            URI file,
            Optional<String> textContent,
            int line,
            int character,
            SymbolIndex index) {
        this.compiler = compiler;
        this.file = file;
        this.textContent = textContent;

        FocusedResult compile = compiler.compileFocused(file, textContent, line, character, false);

        this.task = compile.task;
        this.source = compile.compilationUnit;
        this.index = index;
    }

    public List<Command> find(CodeActionParams params) {
        return params.getContext()
                .getDiagnostics()
                .stream()
                .flatMap(diagnostic -> findCodeActionsForDiagnostic(diagnostic))
                .collect(Collectors.toList());
    }

    private Stream<Command> findCodeActionsForDiagnostic(Diagnostic diagnostic) {
        return codeActionsFor(diagnostic);
    }

    private Stream<Command> codeActionsFor(Diagnostic diagnostic) {
        if (diagnostic.getCode().equals("compiler.err.cant.resolve.location")) {
            return cannotFindSymbolClassName(diagnostic.getMessage())
                    .map(Stream::of)
                    .orElseGet(Stream::empty)
                    .flatMap(this::addImportActions);
        } else return Stream.empty();
    }

    /** Search for symbols on the classpath and sourcepath that match name */
    private Stream<Command> addImportActions(String name) {
        Stream<Command> sourcePath =
                index.allTopLevelClasses()
                        .filter(c -> c.className.equals(name))
                        .map(c -> importClassCommand(c.packageName, c.className));
        Stream<Command> classPath =
                compiler.classPathIndex
                        .topLevelClasses()
                        .filter(c -> c.getSimpleName().equals(name))
                        .map(c -> importClassCommand(c.getPackageName(), c.getSimpleName()));

        return Stream.concat(sourcePath, classPath);
    }

    private Command importClassCommand(String packageName, String className) {
        return new Command(
                "Import " + packageName + "." + className,
                "Java.importClass",
                ImmutableList.of(file.toString(), packageName, className));
    }

    private static final Pattern CANNOT_FIND_SYMBOL = Pattern.compile("class (\\w+)");

    public static Optional<String> cannotFindSymbolClassName(String message) {
        Matcher matcher = CANNOT_FIND_SYMBOL.matcher(message);

        if (matcher.find()) return Optional.of(matcher.group(1));
        else return Optional.empty();
    }
}
