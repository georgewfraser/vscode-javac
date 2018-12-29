package org.javacs_server;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.nio.file.Paths;
import java.util.Set;
import org.junit.Test;

public class ParserTest {
    @Test
    public void testMatchesTitleCase() {
        assertTrue(Parser.matchesTitleCase("FooBar", "fb"));
        assertTrue(Parser.matchesTitleCase("FooBar", "fob"));
        assertTrue(Parser.matchesTitleCase("AnyPrefixFooBar", "fb"));
        assertTrue(Parser.matchesTitleCase("AutocompleteBetweenLines", "ABetweenLines"));
        assertTrue(Parser.matchesTitleCase("UPPERFooBar", "fb"));
        assertFalse(Parser.matchesTitleCase("Foobar", "fb"));
    }

    @Test
    public void findAutocompleteBetweenLines() {
        var rel = Paths.get("src", "org", "javacs_server", "example", "AutocompleteBetweenLines.java");
        var file = LanguageServerFixture.DEFAULT_WORKSPACE_ROOT.resolve(rel);
        assertTrue(Parser.containsWordMatching(file, "ABetweenLines"));
    }

    @Test
    public void findExistingImports() {
        var rel = Paths.get("src", "org", "javacs_server", "doimport");
        var dir = LanguageServerFixture.DEFAULT_WORKSPACE_ROOT.resolve(rel);
        var existing = Parser.existingImports(Set.of(dir));
        assertThat(existing.classes, hasItems("java.util.List"));
        assertThat(existing.packages, hasItems("java.util", "java.io"));
    }
}
