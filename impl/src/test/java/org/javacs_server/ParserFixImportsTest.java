package org.javacs_server;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.util.Collections;
import org.junit.Test;

public class ParserFixImportsTest {
    @Test
    public void findExistingImports() {
        var find = Parser.existingImports(Collections.singleton(JavaCompilerServiceTest.resourcesDir()));
        assertThat(find.classes, hasItem("java.util.List"));
        assertThat(find.packages, hasItem("java.util"));
    }
}
