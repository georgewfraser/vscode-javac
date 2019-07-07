package org.javacs;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.javacs.lsp.Diagnostic;
import org.junit.Before;
import org.junit.Test;

public class WarningsTest {
    protected static final Logger LOG = Logger.getLogger("main");

    private static List<String> errors = new ArrayList<>();

    protected static final JavaLanguageServer server =
            LanguageServerFixture.getJavaLanguageServer(WarningsTest::onError);

    private static void onError(Diagnostic error) {
        var string = String.format("%s(%d)", error.code, error.range.start.line + 1);
        errors.add(string);
    }

    @Before
    public void setup() {
        errors.clear();
    }

    @Test
    public void unusedLocal() {
        server.lint(List.of(FindResource.uri("org/javacs/warn/Unused.java")));
        assertThat(errors, hasItem("unused(7)")); // int unusedLocal
        assertThat(errors, hasItem("unused(10)")); // int unusedPrivate
        assertThat(errors, hasItem("unused(13)")); // int unusedLocalInLambda
        assertThat(errors, hasItem("unused(16)")); // int unusedMethod() { ... }
        assertThat(errors, hasItem("unused(22)")); // private Unused(int i) { }
        assertThat(errors, hasItem("unused(24)")); // private class UnusedClass { }
        assertThat(errors, not(hasItem("unused(6)"))); // test(int unusedParam)
        assertThat(errors, not(hasItem("unused(12)"))); // unusedLambdaParam -> {};
        assertThat(errors, not(hasItem("unused(20)"))); // private Unused() { }
    }

    @Test
    public void referencePackagePrivateClassInFileWithDifferentName() {
        server.lint(List.of(FindResource.uri("org/javacs/example/ReferenceGotoPackagePrivate.java")));
        assertThat(errors, empty());
    }

    // TODO warn on type.equals(otherType)
    // TODO warn on map.get(wrongKeyType)
}
