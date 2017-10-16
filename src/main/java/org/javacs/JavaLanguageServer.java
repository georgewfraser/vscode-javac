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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.ExecuteCommandOptions;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SignatureHelpOptions;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

public class JavaLanguageServer implements LanguageServer {

    private static final Logger LOG = Logger.getLogger("main");
    private static final int maxItems = 50;

    private final CompletableFuture<LanguageClient> client = new CompletableFuture<>();
    private final JavaTextDocumentService textDocuments = new JavaTextDocumentService(client, this);
    private final JavaWorkspaceService workspace =
            new JavaWorkspaceService(client, this, textDocuments);

    private Path workspaceRoot = Paths.get(".");

    private Configured cacheConfigured;
    private JavaSettings cacheSettings;
    private Path cacheWorkspaceRoot;
    private Instant cacheInferConfig = Instant.EPOCH;
    private Set<Path> cacheSourcePath = Collections.emptySet();

    /**
     * Configured java compiler + indices based on workspace settings and inferred source / class
     * paths
     */
    Configured configured() {
        Instant inferConfig = InferConfig.buildFilesModified(workspaceRoot);

        if (cacheConfigured == null
                || !Objects.equals(workspace.settings(), cacheSettings)
                || !Objects.equals(workspaceRoot, cacheWorkspaceRoot)
                || cacheInferConfig.isBefore(inferConfig)
                || !cacheConfigured.index.sourcePath().equals(cacheSourcePath)) {
            cacheConfigured = createCompiler(workspace.settings(), workspaceRoot);
            cacheSettings = workspace.settings();
            cacheWorkspaceRoot = workspaceRoot;
            cacheInferConfig = inferConfig;
            cacheSourcePath = cacheConfigured.index.sourcePath();

            clearDiagnostics();
        }

        return cacheConfigured;
    }

    private Configured createCompiler(JavaSettings settings, Path workspaceRoot) {
        SymbolIndex index =
                new SymbolIndex(
                        workspaceRoot, textDocuments::openFiles, textDocuments::activeContent);
        Set<Path> sourcePath = index.sourcePath();
        Path userHome = Paths.get(System.getProperty("user.home")),
                mavenHome = userHome.resolve(".m2"),
                gradleHome = userHome.resolve(".gradle");
        List<Artifact> externalDependencies =
                Lists.transform(settings.java.externalDependencies, Artifact::parse);
        List<Path> settingsClassPath = Lists.transform(settings.java.classPath, Paths::get);

        InferConfig infer =
                new InferConfig(
                        workspaceRoot,
                        externalDependencies,
                        settingsClassPath,
                        mavenHome,
                        gradleHome);
        Set<Path> classPath = infer.buildClassPath(),
                workspaceClassPath = infer.workspaceClassPath(),
                docPath = infer.buildDocPath();

        // If user does not specify java.externalDependencies, look for javaconfig.json
        // This is for compatibility with the old behavior and should eventually be removed
        if (settings.java.externalDependencies.isEmpty()
                && Files.exists(workspaceRoot.resolve("javaconfig.json"))) {
            LegacyConfig legacy = new LegacyConfig(workspaceRoot);
            Optional<JavacConfig> found = legacy.readJavaConfig(workspaceRoot);

            classPath = found.map(c -> c.classPath).orElse(classPath);
            workspaceClassPath = found.map(c -> c.workspaceClassPath).orElse(workspaceClassPath);
            docPath = found.map(c -> c.docPath).orElse(docPath);
        }

        LOG.info("Inferred configuration: ");
        LOG.info("\tsourcePath:" + Joiner.on(' ').join(sourcePath));
        LOG.info("\tclassPath:" + Joiner.on(' ').join(classPath));
        LOG.info("\tworkspaceClassPath:" + Joiner.on(' ').join(workspaceClassPath));
        LOG.info("\tdocPath:" + Joiner.on(' ').join(docPath));

        JavacHolder compiler =
                JavacHolder.create(sourcePath, Sets.union(classPath, workspaceClassPath));
        Javadocs docs = new Javadocs(sourcePath, docPath, textDocuments::activeContent);
        FindSymbols find = new FindSymbols(index, compiler, textDocuments::activeContent);

        return new Configured(compiler, docs, index, find);
    }

    private void clearDiagnostics() {
        InferConfig.allJavaFiles(workspaceRoot).forEach(this::clearFileDiagnostics);
    }

    private void clearFileDiagnostics(Path file) {
        client.thenAccept(
                c ->
                        c.publishDiagnostics(
                                new PublishDiagnosticsParams(
                                        file.toUri().toString(), new ArrayList<>())));
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        workspaceRoot = Paths.get(params.getRootPath()).toAbsolutePath().normalize();

        InitializeResult result = new InitializeResult();
        ServerCapabilities c = new ServerCapabilities();

        c.setTextDocumentSync(TextDocumentSyncKind.Incremental);
        c.setDefinitionProvider(true);
        c.setCompletionProvider(new CompletionOptions(true, ImmutableList.of(".")));
        c.setHoverProvider(true);
        c.setWorkspaceSymbolProvider(true);
        c.setReferencesProvider(true);
        c.setDocumentSymbolProvider(true);
        c.setCodeActionProvider(true);
        c.setExecuteCommandProvider(
                new ExecuteCommandOptions(ImmutableList.of("Java.importClass")));
        c.setSignatureHelpProvider(new SignatureHelpOptions(ImmutableList.of("(", ",")));

        result.setCapabilities(c);

        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {}

    @Override
    public TextDocumentService getTextDocumentService() {
        return textDocuments;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return workspace;
    }

    public Optional<Element> findSymbol(URI file, int line, int character) {
        Optional<String> content = textDocuments.activeContent(file);
        FocusedResult result =
                configured().compiler.compileFocused(file, content, line, character, false);
        Trees trees = Trees.instance(result.task);
        Function<TreePath, Optional<Element>> findSymbol =
                cursor -> Optional.ofNullable(trees.getElement(cursor));

        return result.cursor.flatMap(findSymbol);
    }

    void installClient(LanguageClient client) {
        this.client.complete(client);

        Handler sendToClient =
                new Handler() {
                    @Override
                    public void publish(LogRecord record) {
                        String message = record.getMessage();

                        if (record.getThrown() != null) {
                            StringWriter trace = new StringWriter();

                            record.getThrown().printStackTrace(new PrintWriter(trace));
                            message += "\n" + trace;
                        }

                        client.logMessage(
                                new MessageParams(
                                        messageType(record.getLevel().intValue()), message));
                    }

                    private MessageType messageType(int level) {
                        if (level >= Level.SEVERE.intValue()) return MessageType.Error;
                        else if (level >= Level.WARNING.intValue()) return MessageType.Warning;
                        else if (level >= Level.INFO.intValue()) return MessageType.Info;
                        else return MessageType.Log;
                    }

                    @Override
                    public void flush() {}

                    @Override
                    public void close() throws SecurityException {}
                };

        Logger.getLogger("").addHandler(sendToClient);
    }

    static void onDiagnostic(Diagnostic<? extends JavaFileObject> diagnostic) {
        Level level = level(diagnostic.getKind());
        String message = diagnostic.getMessage(null);

        LOG.log(level, message);
    }

    private static Level level(Diagnostic.Kind kind) {
        switch (kind) {
            case ERROR:
                return Level.SEVERE;
            case WARNING:
            case MANDATORY_WARNING:
                return Level.WARNING;
            case NOTE:
                return Level.INFO;
            case OTHER:
            default:
                return Level.FINE;
        }
    }

    /**
     * Compile a .java source and emit a .class file.
     *
     * <p>Useful for testing that the language server works when driven by .class files.
     */
    void compile(URI file) {
        Objects.requireNonNull(file, "file is null");

        configured()
                .compiler
                .compileBatch(Collections.singletonMap(file, textDocuments.activeContent(file)));
    }

    private static String jsonStringify(Object value) {
        try {
            return Main.JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
