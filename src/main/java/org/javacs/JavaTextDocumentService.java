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

import com.google.common.base.Joiner;
import com.google.common.collect.Sets;
import com.sun.source.util.Trees;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.CompletableFutures;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;

class JavaTextDocumentService implements TextDocumentService {
    private final CompletableFuture<LanguageClient> client;
    private final JavaLanguageServer server;
    private final Map<URI, VersionedContent> activeDocuments = new HashMap<>();

    JavaTextDocumentService(CompletableFuture<LanguageClient> client, JavaLanguageServer server) {
        this.client = client;
        this.server = server;
    }

    /** Text of file, if it is in the active set */
    Optional<String> activeContent(URI file) {
        return Optional.ofNullable(activeDocuments.get(file)).map(doc -> doc.content);
    }

    /** All open files, not including things like old git-versions in a diff view */
    Set<URI> openFiles() {
        return Sets.filter(activeDocuments.keySet(), uri -> uri.getScheme().equals("file"));
    }

    void doLint(Collection<URI> paths) {
        LOG.info("Lint " + Joiner.on(", ").join(paths));

        List<javax.tools.Diagnostic<? extends JavaFileObject>> errors = new ArrayList<>();
        Map<URI, Optional<String>> content =
                paths.stream().collect(Collectors.toMap(f -> f, this::activeContent));
        DiagnosticCollector<JavaFileObject> compile =
                server.configured().compiler.compileBatch(content);

        errors.addAll(compile.getDiagnostics());

        publishDiagnostics(paths, errors);
    }

    private void publishDiagnostics(
            Collection<URI> touched,
            List<javax.tools.Diagnostic<? extends JavaFileObject>> diagnostics) {
        Map<URI, PublishDiagnosticsParams> files =
                touched.stream()
                        .collect(
                                Collectors.toMap(
                                        uri -> uri,
                                        newUri ->
                                                new PublishDiagnosticsParams(
                                                        newUri.toString(), new ArrayList<>())));

        // Organize diagnostics by file
        for (javax.tools.Diagnostic<? extends JavaFileObject> error : diagnostics) {
            URI uri = error.getSource().toUri();
            PublishDiagnosticsParams publish =
                    files.computeIfAbsent(
                            uri,
                            newUri ->
                                    new PublishDiagnosticsParams(
                                            newUri.toString(), new ArrayList<>()));
            Lints.convert(error).ifPresent(publish.getDiagnostics()::add);
        }

        // If there are no errors in a file, put an empty PublishDiagnosticsParams
        for (URI each : touched) files.putIfAbsent(each, new PublishDiagnosticsParams());

        files.forEach(
                (file, errors) -> {
                    if (touched.contains(file)) {
                        client.join().publishDiagnostics(errors);

                        LOG.info(
                                "Published "
                                        + errors.getDiagnostics().size()
                                        + " errors from "
                                        + file);
                    } else
                        LOG.info(
                                "Ignored "
                                        + errors.getDiagnostics().size()
                                        + " errors from not-open "
                                        + file);
                });
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(
            TextDocumentPositionParams position) {
        Instant started = Instant.now();
        URI uri = URI.create(position.getTextDocument().getUri());
        Optional<String> content = activeContent(uri);
        int line = position.getPosition().getLine() + 1;
        int character = position.getPosition().getCharacter() + 1;

        LOG.info(String.format("completion at %s %d:%d", uri, line, character));

        Configured config = server.configured();
        FocusedResult result = config.compiler.compileFocused(uri, content, line, character, true);
        List<CompletionItem> items =
                Completions.at(result, config.index, config.docs)
                        .limit(server.maxItems)
                        .collect(Collectors.toList());
        CompletionList list = new CompletionList(items.size() == server.maxItems, items);
        Duration elapsed = Duration.between(started, Instant.now());

        if (list.isIncomplete())
            LOG.info(
                    String.format(
                            "Found %d items (incomplete) in %d ms",
                            items.size(), elapsed.toMillis()));
        else LOG.info(String.format("Found %d items in %d ms", items.size(), elapsed.toMillis()));

        return CompletableFuture.completedFuture(Either.forRight(list));
    }

    @Override
    public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem unresolved) {
        return CompletableFutures.computeAsync(
                cancel -> {
                    server.configured().docs.resolveCompletionItem(unresolved);

                    return unresolved;
                });
    }

    @Override
    public CompletableFuture<Hover> hover(TextDocumentPositionParams position) {
        URI uri = URI.create(position.getTextDocument().getUri());
        Optional<String> content = activeContent(uri);
        int line = position.getPosition().getLine() + 1;
        int character = position.getPosition().getCharacter() + 1;

        LOG.info(String.format("hover at %s %d:%d", uri, line, character));

        FocusedResult result =
                server.configured().compiler.compileFocused(uri, content, line, character, false);
        Hover hover = elementAtCursor(result).map(this::hoverText).orElseGet(this::emptyHover);

        return CompletableFuture.completedFuture(hover);
    }

    private Optional<Element> elementAtCursor(FocusedResult compiled) {
        return compiled.cursor.flatMap(
                cursor -> {
                    Element el = Trees.instance(compiled.task).getElement(cursor);

                    return Optional.ofNullable(el);
                });
    }

    private Hover hoverText(Element el) {
        return Hovers.hoverText(el, server.configured().docs);
    }

    private Hover emptyHover() {
        return new Hover(Collections.emptyList(), null);
    }

    @Override
    public CompletableFuture<SignatureHelp> signatureHelp(TextDocumentPositionParams position) {
        URI uri = URI.create(position.getTextDocument().getUri());
        Optional<String> content = activeContent(uri);
        int line = position.getPosition().getLine() + 1;
        int character = position.getPosition().getCharacter() + 1;

        LOG.info(String.format("signatureHelp at %s %d:%d", uri, line, character));

        Configured config = server.configured();
        FocusedResult result = config.compiler.compileFocused(uri, content, line, character, true);
        SignatureHelp help =
                Signatures.help(result, line, character, config.docs).orElseGet(SignatureHelp::new);

        return CompletableFuture.completedFuture(help);
    }

    @Override
    public CompletableFuture<List<? extends Location>> definition(
            TextDocumentPositionParams position) {
        URI uri = URI.create(position.getTextDocument().getUri());
        Optional<String> content = activeContent(uri);
        int line = position.getPosition().getLine() + 1;
        int character = position.getPosition().getCharacter() + 1;

        LOG.info(String.format("definition at %s %d:%d", uri, line, character));

        Configured config = server.configured();
        FocusedResult result = config.compiler.compileFocused(uri, content, line, character, false);
        List<Location> locations =
                References.gotoDefinition(result, config.find)
                        .map(Collections::singletonList)
                        .orElseGet(Collections::emptyList);
        return CompletableFuture.completedFuture(locations);
    }

    @Override
    public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        URI uri = URI.create(params.getTextDocument().getUri());
        Optional<String> content = activeContent(uri);
        int line = params.getPosition().getLine() + 1;
        int character = params.getPosition().getCharacter() + 1;

        LOG.info(String.format("references at %s %d:%d", uri, line, character));

        Configured config = server.configured();
        FocusedResult result = config.compiler.compileFocused(uri, content, line, character, false);
        List<Location> locations =
                References.findReferences(result, config.find)
                        .limit(server.maxItems)
                        .collect(Collectors.toList());

        return CompletableFuture.completedFuture(locations);
    }

    @Override
    public CompletableFuture<List<? extends DocumentHighlight>> documentHighlight(
            TextDocumentPositionParams position) {
        return null;
    }

    @Override
    public CompletableFuture<List<? extends SymbolInformation>> documentSymbol(
            DocumentSymbolParams params) {
        URI uri = URI.create(params.getTextDocument().getUri());
        List<SymbolInformation> symbols =
                server.configured().index.allInFile(uri).collect(Collectors.toList());

        return CompletableFuture.completedFuture(symbols);
    }

    @Override
    public CompletableFuture<List<? extends Command>> codeAction(CodeActionParams params) {
        // Compilation is expensive
        // Don't do it unless a codeAction is actually possible
        // At the moment we only generate code actions in response to diagnostics
        if (params.getContext().getDiagnostics().isEmpty())
            return CompletableFuture.completedFuture(Collections.emptyList());

        URI uri = URI.create(params.getTextDocument().getUri());
        int line = params.getRange().getStart().getLine() + 1;
        int character = params.getRange().getStart().getCharacter() + 1;

        LOG.info(String.format("codeAction at %s %d:%d", uri, line, character));

        Configured config = server.configured();
        List<Command> commands =
                new CodeActions(
                                config.compiler,
                                uri,
                                activeContent(uri),
                                line,
                                character,
                                config.index)
                        .find(params);

        return CompletableFuture.completedFuture(commands);
    }

    @Override
    public CompletableFuture<List<? extends CodeLens>> codeLens(CodeLensParams params) {
        return null;
    }

    @Override
    public CompletableFuture<CodeLens> resolveCodeLens(CodeLens unresolved) {
        return null;
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
        return null;
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> rangeFormatting(
            DocumentRangeFormattingParams params) {
        return null;
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> onTypeFormatting(
            DocumentOnTypeFormattingParams params) {
        return null;
    }

    @Override
    public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
        return null;
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        TextDocumentItem document = params.getTextDocument();
        URI uri = URI.create(document.getUri());

        activeDocuments.put(uri, new VersionedContent(document.getText(), document.getVersion()));

        doLint(Collections.singleton(uri));
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        VersionedTextDocumentIdentifier document = params.getTextDocument();
        URI uri = URI.create(document.getUri());
        VersionedContent existing = activeDocuments.get(uri);
        String newText = existing.content;

        if (document.getVersion() > existing.version) {
            for (TextDocumentContentChangeEvent change : params.getContentChanges()) {
                if (change.getRange() == null)
                    activeDocuments.put(
                            uri, new VersionedContent(change.getText(), document.getVersion()));
                else newText = patch(newText, change);
            }

            activeDocuments.put(uri, new VersionedContent(newText, document.getVersion()));
        } else
            LOG.warning(
                    "Ignored change with version "
                            + document.getVersion()
                            + " <= "
                            + existing.version);
    }

    private String patch(String sourceText, TextDocumentContentChangeEvent change) {
        try {
            Range range = change.getRange();
            BufferedReader reader = new BufferedReader(new StringReader(sourceText));
            StringWriter writer = new StringWriter();

            // Skip unchanged lines
            int line = 0;

            while (line < range.getStart().getLine()) {
                writer.write(reader.readLine() + '\n');
                line++;
            }

            // Skip unchanged chars
            for (int character = 0; character < range.getStart().getCharacter(); character++)
                writer.write(reader.read());

            // Write replacement text
            writer.write(change.getText());

            // Skip replaced text
            reader.skip(change.getRangeLength());

            // Write remaining text
            while (true) {
                int next = reader.read();

                if (next == -1) return writer.toString();
                else writer.write(next);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        TextDocumentIdentifier document = params.getTextDocument();
        URI uri = URI.create(document.getUri());

        // Remove from source cache
        activeDocuments.remove(uri);

        // Clear diagnostics
        client.join()
                .publishDiagnostics(
                        new PublishDiagnosticsParams(uri.toString(), new ArrayList<>()));
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        // Re-lint all active documents
        doLint(openFiles());
    }

    private static final Logger LOG = Logger.getLogger("main");
}
