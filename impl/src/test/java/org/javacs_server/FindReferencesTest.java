package org.javacs_server;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.javacs.lsp.Position;
import org.javacs.lsp.ReferenceParams;
import org.javacs.lsp.TextDocumentIdentifier;
import org.javacs_server.lsp.*;
import org.junit.Test;

public class FindReferencesTest {
    private static final Logger LOG = Logger.getLogger("main");

    private static final JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();

    protected List<String> items(String file, int row, int column) {
        var uri = FindResource.uri(file);
        var params = new ReferenceParams();

        params.textDocument = new TextDocumentIdentifier(uri);
        params.position = new Position(row - 1, column - 1);

        var locations = server.findReferences(params);
        var strings = new ArrayList<String>();
        for (var l : locations) {
            var fileName = Parser.fileName(l.uri);
            var line = l.range.start.line;
            strings.add(String.format("%s(%d)", fileName, line + 1));
        }
        return strings;
    }

    @Test
    public void findAllReferences() {
        assertThat(items("/org/javacs_server//example/GotoOther.java", 6, 30), not(empty()));
    }

    @Test
    public void findConstructorReferences() {
        assertThat(items("/org/javacs_server//example/ConstructorRefs.java", 4, 10), contains("ConstructorRefs.java(9)"));
    }
}
