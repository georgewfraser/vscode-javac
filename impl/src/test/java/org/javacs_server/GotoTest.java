package org.javacs_server;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.javacs.lsp.Position;
import org.javacs.lsp.TextDocumentIdentifier;
import org.javacs.lsp.TextDocumentPositionParams;
import org.javacs_server.lsp.*;
import org.junit.Ignore;
import org.junit.Test;

public class GotoTest {
    private static final String file = "/org/javacs_server//example/Goto.java";
    private static final String defaultConstructorFile = "/org/javacs_server//example/GotoDefaultConstructor.java";

    @Test
    public void localVariable() {
        var suggestions = doGoto(file, 9, 8);

        assertThat(suggestions, contains("Goto.java:5"));
    }

    @Test
    public void defaultConstructor() {
        var suggestions = doGoto(defaultConstructorFile, 4, 45);

        assertThat(suggestions, contains("GotoDefaultConstructor.java:3"));
    }

    @Test
    public void constructor() {
        var suggestions = doGoto(file, 10, 20);

        assertThat(suggestions, contains("Goto.java:3"));
    }

    @Test
    public void className() {
        var suggestions = doGoto(file, 15, 8);

        assertThat(suggestions, contains("Goto.java:3"));
    }

    @Test
    public void staticField() {
        var suggestions = doGoto(file, 12, 21);

        assertThat(suggestions, contains("Goto.java:36"));
    }

    @Test
    public void field() {
        var suggestions = doGoto(file, 13, 21);

        assertThat(suggestions, contains("Goto.java:37"));
    }

    @Test
    public void staticMethod() {
        var suggestions = doGoto(file, 15, 13);

        assertThat(suggestions, contains("Goto.java:38"));
    }

    @Test
    public void method() {
        var suggestions = doGoto(file, 16, 13);

        assertThat(suggestions, contains("Goto.java:41"));
    }

    @Test
    public void staticMethodReference() {
        var suggestions = doGoto(file, 18, 26);

        assertThat(suggestions, contains("Goto.java:38"));
    }

    @Test
    public void methodReference() {
        var suggestions = doGoto(file, 19, 26);

        assertThat(suggestions, contains("Goto.java:41"));
    }

    @Test
    public void otherStaticMethod() {
        var suggestions = doGoto(file, 28, 24);

        assertThat(suggestions, contains(startsWith("GotoOther.java:")));
    }

    @Test
    public void otherMethod() {
        var suggestions = doGoto(file, 29, 17);

        assertThat(suggestions, contains(startsWith("GotoOther.java:")));
    }

    @Test
    public void otherCompiledFile() {
        var suggestions = doGoto(file, 28, 24);

        assertThat(suggestions, contains(startsWith("GotoOther.java:")));
    }

    @Test
    @Ignore // TODO
    public void typeParam() {
        var suggestions = doGoto(file, 45, 11);

        assertThat(suggestions, contains("Goto.java:3"));
    }

    @Test
    public void gotoEnum() {
        String file = "/org/javacs_server//example/GotoEnum.java";

        assertThat(doGoto(file, 5, 30), not(empty()));
        assertThat(doGoto(file, 5, 35), not(empty()));
    }

    private static final JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();

    private List<String> doGoto(String file, int row, int column) {
        TextDocumentIdentifier document = new TextDocumentIdentifier();

        document.uri = FindResource.uri(file);

        Position position = new Position();

        position.line = row;
        position.character = column;

        TextDocumentPositionParams p = new TextDocumentPositionParams();

        p.textDocument = document;
        p.position = position;

        var locations = server.gotoDefinition(p);
        var strings = new ArrayList<String>();
        for (var l : locations) {
            var fileName = Paths.get(l.uri).getFileName();
            var start = l.range.start;
            strings.add(String.format("%s:%d", fileName, start.line + 1));
        }
        return strings;
    }
}
