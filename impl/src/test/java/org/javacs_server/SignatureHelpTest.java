package org.javacs_server;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.io.IOException;
import org.javacs.lsp.Position;
import org.javacs.lsp.SignatureHelp;
import org.javacs.lsp.TextDocumentIdentifier;
import org.javacs.lsp.TextDocumentPositionParams;
import org.javacs_server.lsp.*;
import org.junit.Test;

public class SignatureHelpTest {
    @Test
    public void signatureHelp() throws IOException {
        var help = doHelp("/org/javacs_server/example/SignatureHelp.java", 7, 36);

        assertThat(help.signatures, hasSize(2));
    }

    @Test
    public void partlyFilledIn() throws IOException {
        var help = doHelp("/org/javacs_server/example/SignatureHelp.java", 8, 39);

        assertThat(help.signatures, hasSize(2));
        assertThat(help.activeSignature, equalTo(1));
        assertThat(help.activeParameter, equalTo(1));
    }

    @Test
    public void constructor() throws IOException {
        var help = doHelp("/org/javacs_server/example/SignatureHelp.java", 9, 27);

        assertThat(help.signatures, hasSize(1));
        assertThat(help.signatures.get(0).label, startsWith("SignatureHelp"));
    }

    @Test
    public void platformConstructor() throws IOException {
        var help = doHelp("/org/javacs_server/example/SignatureHelp.java", 10, 26);

        assertThat(help.signatures, not(empty()));
        assertThat(help.signatures.get(0).label, startsWith("ArrayList"));
        // TODO
        // assertThat(help.signatures.get(0).documentation, not(nullValue()));
    }

    private static final JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();

    private SignatureHelp doHelp(String file, int row, int column) throws IOException {
        var document = new TextDocumentIdentifier();

        document.uri = FindResource.uri(file);

        var position = new Position();

        position.line = row - 1;
        position.character = column - 1;

        var p = new TextDocumentPositionParams();

        p.textDocument = document;
        p.position = position;

        return server.signatureHelp(p).get();
    }
}
