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

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import com.google.common.base.Joiner;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.junit.BeforeClass;
import org.junit.Test;

public class SearchTest {
    private static final Logger LOG = Logger.getLogger("main");

    private static final JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();

    @BeforeClass
    public static void openSource() throws URISyntaxException, IOException {
        URI uri = FindResource.uri("/org/javacs/example/AutocompleteBetweenLines.java");
        String textContent = Joiner.on("\n").join(Files.readAllLines(Paths.get(uri)));
        TextDocumentItem document = new TextDocumentItem();

        document.setUri(uri.toString());
        document.setText(textContent);

        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(document, null));
    }

    private static Set<String> searchWorkspace(String query) {
        try {
            return server.getWorkspaceService()
                    .symbol(new WorkspaceSymbolParams(query))
                    .get()
                    .stream()
                    .map(result -> result.getName())
                    .collect(Collectors.toSet());
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private static Set<String> searchFile(URI uri) {
        try {
            return server.getTextDocumentService()
                    .documentSymbol(
                            new DocumentSymbolParams(new TextDocumentIdentifier(uri.toString())))
                    .get()
                    .stream()
                    .map(result -> result.getName())
                    .collect(Collectors.toSet());
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void all() {
        Set<String> all = searchWorkspace("");

        assertThat(all, not(empty()));
    }

    @Test
    public void searchClasses() {
        Set<String> all = searchWorkspace("ABetweenLines");

        assertThat(all, hasItem("AutocompleteBetweenLines"));
    }

    @Test
    public void searchMethods() {
        Set<String> all = searchWorkspace("mStatic");

        assertThat(all, hasItem("methodStatic"));
    }

    @Test
    public void symbolsInFile() {
        String path = "/org/javacs/example/AutocompleteMemberFixed.java";
        Set<String> all = searchFile(FindResource.uri(path));

        assertThat(
                all,
                hasItems(
                        "methodStatic", "method",
                        "methodStaticPrivate", "methodPrivate"));

        assertThat(
                all,
                hasItems(
                        "fieldStatic", "field",
                        "fieldStaticPrivate", "fieldPrivate"));
    }

    @Test
    public void explicitConstructor() {
        String path = "/org/javacs/example/ReferenceConstructor.java";
        Set<String> all = searchFile(FindResource.uri(path));

        assertThat("includes explicit constructor", all, hasItem("ReferenceConstructor"));
    }
}
