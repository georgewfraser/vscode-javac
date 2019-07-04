package org.javacs;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import org.javacs.lsp.PublishDiagnosticsParams;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

public class JavaCompilerServiceTest {
    static {
        Main.setRootFormat();
    }

    private JavaCompilerService compiler =
            new JavaCompilerService(Collections.emptySet(), Collections.emptySet(), Collections.emptySet());

    static Path simpleProjectSrc() {
        return Paths.get("src/test/test-project/simple").normalize();
    }

    @Before
    public void setWorkspaceRoot() {
        FileStore.setWorkspaceRoots(Set.of(simpleProjectSrc()));
    }

    static String contents(String resourceFile) {
        var root = JavaCompilerServiceTest.simpleProjectSrc();
        var file = root.resolve(resourceFile);
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        var join = new StringJoiner("\n");
        for (var l : lines) join.add(l);
        return join.toString();
    }

    static URI resourceUri(String resourceFile) {
        var root = JavaCompilerServiceTest.simpleProjectSrc();
        var file = root.resolve(resourceFile);
        return file.toUri();
    }

    @Test
    public void element() {
        var uri = resourceUri("HelloWorld.java");
        var found = compiler.compileFocus(uri, 3, 24).element(uri, 3, 24).get();

        assertThat(found.getSimpleName(), hasToString(containsString("println")));
    }

    @Test
    public void elementWithError() {
        var uri = resourceUri("CompleteMembers.java");
        var found = compiler.compileFocus(uri, 3, 12).element(uri, 3, 12);

        assertThat(found, notNullValue());
    }

    private List<String> completionNames(List<Completion> found) {
        var result = new ArrayList<String>();
        for (var c : found) {
            if (c.element != null) result.add(c.element.getSimpleName().toString());
            else if (c.packagePart != null) result.add(c.packagePart.name);
            else if (c.keyword != null) result.add(c.keyword);
            else if (c.className != null) result.add(StringSearch.lastName(c.className.name));
            else if (c.snippet != null) result.add(c.snippet.snippet);
        }
        return result;
    }

    private List<String> elementNames(List<Element> found) {
        return found.stream().map(e -> e.getSimpleName().toString()).collect(Collectors.toList());
    }

    @Test
    public void identifiers() {
        var uri = resourceUri("CompleteIdentifiers.java");
        var focus = compiler.compileFocus(uri, 13, 21);
        var found = focus.scopeMembers(uri, 13, 21, "complete");
        var names = elementNames(found);
        assertThat(names, hasItem("completeLocal"));
        assertThat(names, hasItem("completeParam"));
        //        assertThat(names, hasItem("super"));
        //        assertThat(names, hasItem("this"));
        assertThat(names, hasItem("completeOtherMethod"));
        assertThat(names, hasItem("completeInnerField"));
        assertThat(names, hasItem("completeOuterField"));
        assertThat(names, hasItem("completeOuterStatic"));
        // assertThat(names, hasItem("CompleteIdentifiers"));
    }

    @Test
    public void identifiersInMiddle() {
        var uri = resourceUri("CompleteInMiddle.java");
        var focus = compiler.compileFocus(uri, 13, 21);
        var found = focus.scopeMembers(uri, 13, 21, "complete");
        var names = elementNames(found);
        assertThat(names, hasItem("completeLocal"));
        assertThat(names, hasItem("completeParam"));
        //        assertThat(names, hasItem("super"));
        //        assertThat(names, hasItem("this"));
        assertThat(names, hasItem("completeOtherMethod"));
        assertThat(names, hasItem("completeInnerField"));
        assertThat(names, hasItem("completeOuterField"));
        assertThat(names, hasItem("completeOuterStatic"));
        // assertThat(names, hasItem("CompleteInMiddle"));
    }

    @Test
    public void completeIdentifiers() {
        var uri = resourceUri("CompleteIdentifiers.java");
        var ctx = compiler.parseFile(uri).completionContext(13, 21).get();
        var focus = compiler.compileFocus(uri, ctx.line, ctx.character);
        var found = focus.completeIdentifiers(uri, ctx.line, ctx.character, ctx.inClass, ctx.inMethod, ctx.partialName);
        var names = completionNames(found);
        assertThat(names, hasItem("completeLocal"));
        assertThat(names, hasItem("completeParam"));
        //        assertThat(names, hasItem("super"));
        //        assertThat(names, hasItem("this"));
        assertThat(names, hasItem("completeOtherMethod"));
        assertThat(names, hasItem("completeInnerField"));
        assertThat(names, hasItem("completeOuterField"));
        assertThat(names, hasItem("completeOuterStatic"));
        //        assertThat(names, hasItem("CompleteIdentifiers"));
    }

    @Test
    public void members() {
        var uri = resourceUri("CompleteMembers.java");
        var focus = compiler.compileFocus(uri, 3, 14);
        var found = focus.completeMembers(uri, 3, 14, false);
        var names = completionNames(found);
        assertThat(names, hasItem("subMethod"));
        assertThat(names, hasItem("superMethod"));
        assertThat(names, hasItem("equals"));
    }

    @Test
    public void completeMembers() {
        var uri = resourceUri("CompleteMembers.java");
        var ctx = compiler.parseFile(uri).completionContext(3, 15).get();
        var focus = compiler.compileFocus(uri, ctx.line, ctx.character);
        var found = focus.completeMembers(uri, ctx.line, ctx.character, false);
        var names = completionNames(found);
        assertThat(names, hasItem("subMethod"));
        assertThat(names, hasItem("superMethod"));
        assertThat(names, hasItem("equals"));
    }

    @Test
    public void completeExpression() {
        var uri = resourceUri("CompleteExpression.java");
        var ctx = compiler.parseFile(uri).completionContext(3, 37).get();
        var focus = compiler.compileFocus(uri, ctx.line, ctx.character);
        var found = focus.completeMembers(uri, ctx.line, ctx.character, false);
        var names = completionNames(found);
        assertThat(names, hasItem("instanceMethod"));
        assertThat(names, not(hasItem("create")));
        assertThat(names, hasItem("equals"));
    }

    @Test
    public void completeClass() {
        var uri = resourceUri("CompleteClass.java");
        var ctx = compiler.parseFile(uri).completionContext(3, 23).get();
        var focus = compiler.compileFocus(uri, ctx.line, ctx.character);
        var found = focus.completeMembers(uri, ctx.line, ctx.character, false);
        var names = completionNames(found);
        assertThat(names, hasItems("staticMethod", "staticField"));
        assertThat(names, hasItems("class"));
        assertThat(names, not(hasItem("instanceMethod")));
        assertThat(names, not(hasItem("instanceField")));
    }

    @Test
    public void completeImports() {
        var uri = resourceUri("CompleteImports.java");
        var ctx = compiler.parseFile(uri).completionContext(1, 18).get();
        var focus = compiler.compileFocus(uri, ctx.line, ctx.character);
        var found = focus.completeMembers(uri, ctx.line, ctx.character, false);
        var names = completionNames(found);
        assertThat(names, hasItem("List"));
        assertThat(names, hasItem("concurrent"));
    }

    @Test
    public void overloads() {
        var uri = resourceUri("Overloads.java");
        var found = compiler.compileFocus(uri, 3, 15).methodInvocation(uri, 3, 15).get();
        var strings = found.overloads.stream().map(Object::toString).collect(Collectors.toList());

        assertThat(strings, hasItem(containsString("print(int)")));
        assertThat(strings, hasItem(containsString("print(java.lang.String)")));
    }

    @Test
    public void reportErrors() {
        var uri = resourceUri("HasError.java");
        var files = Collections.singleton(uri);
        var diags = compiler.compileUris(files).reportErrors();
        assertThat(diags, not(empty()));
    }

    private static List<String> errorStrings(Collection<PublishDiagnosticsParams> list) {
        var strings = new ArrayList<String>();
        for (var group : list) {
            for (var d : group.diagnostics) {
                var file = StringSearch.fileName(group.uri);
                var line = d.range.start.line;
                var msg = d.message;
                var string = String.format("%s(%d): %s", file, line, msg);
                strings.add(string);
            }
        }
        return strings;
    }

    // TODO get these back somehow
    @Test
    @Ignore
    public void errorProne() {
        var uri = resourceUri("ErrorProne.java");
        var files = Collections.singleton(uri);
        var diags = compiler.compileUris(files).reportErrors();
        var strings = errorStrings(diags);
        assertThat(strings, hasItem(containsString("ErrorProne.java(7): [CollectionIncompatibleType]")));
    }

    // TODO get these back somehow
    @Test
    @Ignore
    public void unusedVar() {
        var uri = resourceUri("UnusedVar.java");
        var files = Collections.singleton(uri);
        var diags = compiler.compileUris(files).reportErrors();
        var strings = errorStrings(diags);
        assertThat(strings, hasItem(containsString("UnusedVar.java(3): [Unused]")));
    }

    @Test
    public void localDoc() {
        var uri = resourceUri("LocalMethodDoc.java");
        var invocation = compiler.compileFocus(uri, 3, 21).methodInvocation(uri, 3, 21).get();
        var method = invocation.activeMethod.get();
        var ptr = new Ptr(method);
        var file = compiler.docs().find(ptr).get();
        var parse = compiler.parseJavaFileObject(file);
        var path = parse.fuzzyFind(ptr).get();
        var doc = parse.doc(path);
        assertThat(doc.toString(), containsString("A great method"));
    }

    @Test
    public void fixImports() {
        var uri = resourceUri("MissingImport.java");
        var qualifiedNames = compiler.compileFile(uri).fixImports(uri);
        assertThat(qualifiedNames, hasItem("java.util.List"));
    }

    @Test
    public void dontImportEnum() {
        var uri = resourceUri("DontImportEnum.java");
        var qualifiedNames = compiler.compileFile(uri).fixImports(uri);
        assertThat(qualifiedNames, contains("java.nio.file.AccessMode", "java.util.ArrayList"));
    }

    @Test
    public void matchesPartialName() {
        assertTrue(StringSearch.matchesPartialName("foobar", "foo"));
        assertFalse(StringSearch.matchesPartialName("foo", "foobar"));
    }

    @Test
    public void pruneMethods() {
        var actual = compiler.parseFile(resourceUri("PruneMethods.java")).prune(6, 19);
        var expected = contents("PruneMethods_erased.java");
        assertThat(actual, equalToIgnoringWhiteSpace(expected));
    }

    @Test
    public void pruneToEndOfBlock() {
        var actual = compiler.parseFile(resourceUri("PruneToEndOfBlock.java")).prune(4, 18);
        var expected = contents("PruneToEndOfBlock_erased.java");
        assertThat(actual, equalToIgnoringWhiteSpace(expected));
    }

    @Test
    public void pruneMiddle() {
        var actual = compiler.parseFile(resourceUri("PruneMiddle.java")).prune(4, 12);
        var expected = contents("PruneMiddle_erased.java");
        assertThat(actual, equalToIgnoringWhiteSpace(expected));
    }

    @Test
    public void pruneDot() {
        var actual = compiler.parseFile(resourceUri("PruneDot.java")).prune(3, 11);
        var expected = contents("PruneDot_erased.java");
        assertThat(actual, equalToIgnoringWhiteSpace(expected));
    }

    @Test
    public void pruneWords() {
        var actual = compiler.parseFile(resourceUri("PruneWords.java")).prune("word");
        var expected = contents("PruneWords_erased.java");
        assertThat(actual, equalToIgnoringWhiteSpace(expected));
    }
}
