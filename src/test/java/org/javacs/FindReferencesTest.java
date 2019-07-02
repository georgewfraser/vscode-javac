package org.javacs;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import org.javacs.lsp.*;
import org.junit.Test;

public class FindReferencesTest {
    private static final JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();

    protected List<String> items(String file, int row, int column) {
        var uri = FindResource.uri(file);
        var params = new ReferenceParams();

        params.textDocument = new TextDocumentIdentifier(uri);
        params.position = new Position(row - 1, column - 1);

        var locations = server.findReferences(params).orElse(List.of());
        var strings = new ArrayList<String>();
        for (var l : locations) {
            var fileName = StringSearch.fileName(l.uri);
            var line = l.range.start.line;
            strings.add(String.format("%s(%d)", fileName, line + 1));
        }
        return strings;
    }

    @Test
    public void findAllReferences() {
        assertThat(items("/org/javacs/example/GotoOther.java", 6, 30), not(empty()));
    }

    @Test
    public void findInterfaceReference() {
        assertThat(
                items("/org/javacs/example/GotoImplementation.java", 14, 26), contains("GotoImplementation.java(5)"));
    }

    @Test
    public void findConstructorReferences() {
        assertThat(items("/org/javacs/example/ConstructorRefs.java", 4, 10), contains("ConstructorRefs.java(9)"));
    }

    @Test
    public void referenceIndirectImport() {
        assertThat(
                items("/org/javacs/other/ImportIndirectly.java", 4, 25), contains("ReferenceIndirectImport.java(9)"));
    }

    @Test
    public void findStackedFieldReferences() {
        var file = "/org/javacs/example/StackedFieldReferences.java";
        assertThat(items(file, 4, 9), contains("StackedFieldReferences.java(7)"));
        assertThat(items(file, 4, 12), contains("StackedFieldReferences.java(8)"));
        assertThat(items(file, 4, 15), contains("StackedFieldReferences.java(9)"));
    }
}
