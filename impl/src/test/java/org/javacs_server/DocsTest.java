package org.javacs_server;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.util.Set;
import org.junit.Test;

public class DocsTest {
    @Test
    public void classDoc() {
        var sourcePath = Set.of(JavaCompilerServiceTest.resourcesDir());
        var docs = new Docs(sourcePath);
        var tree = docs.classDoc("ClassDoc");
        assertTrue(tree.isPresent());
        assertThat(tree.get().getFirstSentence(), hasToString("A great class"));
    }

    @Test
    public void memberDoc() {
        var sourcePath = Set.of(JavaCompilerServiceTest.resourcesDir());
        var docs = new Docs(sourcePath);
        var tree = docs.memberDoc("LocalMethodDoc", "targetMethod");
        assertTrue(tree.isPresent());
        assertThat(tree.get().getFirstSentence(), hasToString("A great method"));
    }

    @Test
    public void platformDoc() {
        var docs = new Docs(Set.of());
        var tree = docs.classDoc("java.util.List");
        assertTrue(tree.isPresent());
    }
}
