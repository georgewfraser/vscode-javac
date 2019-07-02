package org.javacs;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.util.Collections;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

public class DocsTest {

    @Before
    public void setWorkspaceRoot() {
        FileStore.setWorkspaceRoots(Set.of(LanguageServerFixture.SIMPLE_WORKSPACE_ROOT));
    }

    private JavaCompilerService compiler =
            new JavaCompilerService(Collections.emptySet(), Collections.emptySet(), Collections.emptySet());

    @Test
    public void classDoc() {
        var sourcePath = Set.of(JavaCompilerServiceTest.simpleProjectSrc());
        var docs = new Docs(sourcePath);
        var ptr = new Ptr("ClassDoc");
        var file = docs.find(ptr).get();
        var parse = compiler.parseJavaFileObject(file);
        var path = parse.fuzzyFind(ptr).get();
        var tree = parse.doc(path);
        assertThat(tree.getFirstSentence(), hasToString("A great class"));
    }

    @Test
    public void memberDoc() {
        var sourcePath = Set.of(JavaCompilerServiceTest.simpleProjectSrc());
        var docs = new Docs(sourcePath);
        var ptr = new Ptr("LocalMethodDoc#targetMethod(int)");
        var file = docs.find(ptr).get();
        var parse = compiler.parseJavaFileObject(file);
        var path = parse.fuzzyFind(ptr).get();
        var tree = parse.doc(path);
        assertThat(tree.getFirstSentence(), hasToString("A great method"));
    }

    @Test
    public void platformDoc() {
        var docs = new Docs(Set.of());
        var ptr = new Ptr("java.util/List");
        var file = docs.find(ptr).get();
        var parse = compiler.parseJavaFileObject(file);
        var path = parse.fuzzyFind(ptr).get();
        var tree = parse.doc(path);
        assertThat(tree.getFirstSentence(), not(empty()));
    }
}
