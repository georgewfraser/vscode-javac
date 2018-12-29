package org.javacs_server;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Set;
import java.util.StringJoiner;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.javacs.lsp.DidOpenTextDocumentParams;
import org.javacs.lsp.DocumentSymbolParams;
import org.javacs.lsp.TextDocumentIdentifier;
import org.javacs.lsp.TextDocumentItem;
import org.javacs.lsp.WorkspaceSymbolParams;
import org.javacs_server.lsp.*;
import org.junit.BeforeClass;
import org.junit.Test;

public class SearchTest {
    private static final Logger LOG = Logger.getLogger("main");

    private static final JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();

    @BeforeClass
    public static void openSource() throws IOException {
        var uri = FindResource.uri("/org/javacs_server/example/AutocompleteBetweenLines.java");
        var textContent = new StringJoiner("\n");
        for (var line : Files.readAllLines(Paths.get(uri))) {
            textContent.add(line);
        }
        var document = new TextDocumentItem();

        document.uri = uri;
        document.text = textContent.toString();

        server.didOpenTextDocument(new DidOpenTextDocumentParams(document));
    }

    private static Set<String> searchWorkspace(String query, int limit) {
        return server.workspaceSymbols(new WorkspaceSymbolParams(query))
                .stream()
                .map(result -> result.name)
                .limit(limit)
                .collect(Collectors.toSet());
    }

    private static Set<String> searchFile(URI uri) {
        return server.documentSymbol(new DocumentSymbolParams(new TextDocumentIdentifier(uri)))
                .stream()
                .map(result -> result.name)
                .collect(Collectors.toSet());
    }

    @Test
    public void all() {
        var all = searchWorkspace("", 100);

        assertThat(all, not(empty()));
    }

    @Test
    public void searchClasses() {
        var all = searchWorkspace("ABetweenLines", Integer.MAX_VALUE);

        assertThat(all, hasItem("AutocompleteBetweenLines"));
    }

    @Test
    public void searchMethods() {
        var all = searchWorkspace("mStatic", Integer.MAX_VALUE);

        assertThat(all, hasItem("methodStatic"));
    }

    @Test
    public void symbolsInFile() {
        var path = "/org/javacs_server/example/AutocompleteMemberFixed.java";
        var all = searchFile(FindResource.uri(path));

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
        var path = "/org/javacs_server/example/ReferenceConstructor.java";
        var all = searchFile(FindResource.uri(path));

        assertThat("includes explicit constructor", all, hasItem("ReferenceConstructor"));
    }
}
