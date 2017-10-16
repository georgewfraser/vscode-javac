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

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.ApplyWorkspaceEditParams;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.WorkspaceService;

class JavaWorkspaceService implements WorkspaceService {
    private final CompletableFuture<LanguageClient> client;
    private final JavaLanguageServer server;
    private final JavaTextDocumentService textDocuments;
    private JavaSettings settings = new JavaSettings();

    JavaWorkspaceService(
            CompletableFuture<LanguageClient> client,
            JavaLanguageServer server,
            JavaTextDocumentService textDocuments) {
        this.client = client;
        this.server = server;
        this.textDocuments = textDocuments;
    }

    JavaSettings settings() {
        return settings;
    }

    @Override
    public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
        LOG.info(params.toString());

        switch (params.getCommand()) {
            case "Java.importClass":
                String fileString = (String) params.getArguments().get(0);
                URI fileUri = URI.create(fileString);
                String packageName = (String) params.getArguments().get(1);
                String className = (String) params.getArguments().get(2);
                FocusedResult compiled =
                        server.configured()
                                .compiler
                                .compileFocused(
                                        fileUri, textDocuments.activeContent(fileUri), 1, 1, false);

                if (compiled.compilationUnit.getSourceFile().toUri().equals(fileUri)) {
                    List<TextEdit> edits =
                            new RefactorFile(compiled.task, compiled.compilationUnit)
                                    .addImport(packageName, className);

                    client.join()
                            .applyEdit(
                                    new ApplyWorkspaceEditParams(
                                            new WorkspaceEdit(
                                                    Collections.singletonMap(fileString, edits))));
                }

                break;
            default:
                LOG.warning("Don't know what to do with " + params.getCommand());
        }

        return CompletableFuture.completedFuture("Done");
    }

    @Override
    public CompletableFuture<List<? extends SymbolInformation>> symbol(
            WorkspaceSymbolParams params) {
        List<SymbolInformation> infos =
                server.configured()
                        .index
                        .search(params.getQuery())
                        .limit(server.maxItems)
                        .collect(Collectors.toList());

        return CompletableFuture.completedFuture(infos);
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams change) {
        settings = Main.JSON.convertValue(change.getSettings(), JavaSettings.class);
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        textDocuments.doLint(textDocuments.openFiles());
    }

    private static final Logger LOG = Logger.getLogger("main");
}
