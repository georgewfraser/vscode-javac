package org.javacs_server;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import org.javacs.lsp.CodeLens;
import org.javacs.lsp.CodeLensParams;
import org.javacs.lsp.TextDocumentIdentifier;
import org.javacs_server.lsp.*;
import org.junit.Test;

public class CodeLensTest {

    private static final JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();

    private List<? extends CodeLens> lenses(String file) {
        var uri = FindResource.uri(file);
        var params = new CodeLensParams(new TextDocumentIdentifier(uri));
        var lenses = server.codeLens(params);
        var resolved = new ArrayList<CodeLens>();
        for (var lens : lenses) {
            if (lens.command == null) {
                lens = server.resolveCodeLens(lens);
            }
            resolved.add(lens);
        }
        return resolved;
    }

    private List<String> commands(List<? extends CodeLens> lenses) {
        var commands = new ArrayList<String>();
        for (var lens : lenses) {
            commands.add(String.format("%s(%s)", lens.command.command, lens.command.arguments));
        }
        return commands;
    }

    private List<String> titles(List<? extends CodeLens> lenses) {
        var titles = new ArrayList<String>();
        for (var lens : lenses) {
            var line = lens.range.start.line + 1;
            var title = lens.command.title;
            titles.add(line + ":" + title);
        }
        return titles;
    }

    @Test
    public void testMethods() {
        var lenses = lenses("/org/javacs_server/example/HasTest.java");
        assertThat(lenses, not(empty()));

        var commands = commands(lenses);
        assertThat(commands, hasItem(containsString("\"HasTest\",null")));
        assertThat(commands, hasItem(containsString("\"HasTest\",\"testMethod\"")));
        assertThat(commands, hasItem(containsString("\"HasTest\",\"otherTestMethod\"")));
    }

    @Test
    public void constructorReferences() {
        var lenses = lenses("/org/javacs_server/example/ConstructorRefs.java");
        assertThat(lenses, not(empty()));

        var titles = titles(lenses);
        assertThat(titles, hasItem("4:2 references"));
        assertThat(titles, hasItem("6:2 references"));
    }

    @Test
    public void enumConstants() {
        var lenses = lenses("/org/javacs_server/example/DontShowEnumConstRefs.java");
        var titles = titles(lenses);
        assertThat(titles, not(hasItem("4:0 references")));
    }
}
