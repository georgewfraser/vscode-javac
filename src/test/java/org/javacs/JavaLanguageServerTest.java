package org.javacs;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import org.javacs.lsp.DidOpenTextDocumentParams;
import org.javacs.lsp.TextDocumentItem;
import org.junit.Test;

public class JavaLanguageServerTest {

    @Test
    public void LintShouldNotCrashOnCodeWithMissingTypeIdentifier() {
        String filePath = "src/test/examples/missing-type-identifier/Sample.java";
        TextDocumentItem textDocument = new TextDocumentItem();
        textDocument.uri = URI.create("file:///" + filePath);
        try {
            textDocument.text = Files.readString(Path.of(filePath));
        } catch (IOException e) {
            System.out.println(e.toString());
        }
        textDocument.version = 1;
        textDocument.languageId = "java";
        JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();
        server.didOpenTextDocument(new DidOpenTextDocumentParams(textDocument));

        // Should not fail
        server.lint(Collections.singleton(Paths.get(textDocument.uri)));
    }
}
