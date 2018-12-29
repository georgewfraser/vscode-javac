package org.javacs_server;

import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import org.junit.Ignore;
import org.junit.Test;

public class CompletionsScopesTest extends CompletionsBase {
    @Test
    public void staticSub() throws IOException {
        var file = "/org/javacs_server/example/AutocompleteScopes.java";

        // Static method
        var suggestions = insertText(file, 15, 14);

        // Locals
        assertThat(suggestions, hasItems("testLocalVariables", "testArguments"));
        // Static methods in enclosing scopes
        assertThat(suggestions, hasItems("testStatic"));
        assertThat(suggestions, hasItems("testOuterStaticMethod"));
        // Virtual methods in enclosing scopes
        assertThat(suggestions, not(hasItems("testInner")));
        assertThat(suggestions, hasItems("test"));
        // TODO this is not accessible
        // assertThat(suggestions, not(hasItems("testOuterMethods")));
        // Inherited static methods
        assertThat(suggestions, hasItems("testInheritedStaticMethod"));
        // Inherited virtual methods
        assertThat(suggestions, hasItems("testInheritedMethods"));
        // this/super in enclosing scopes
        assertThat(suggestions, hasItems("this"));
    }

    @Test
    public void staticSubThisSuper() throws IOException {
        var file = "/org/javacs_server/example/AutocompleteScopes.java";

        // StaticSub.this, StaticSub.super
        assertThat(insertText(file, 37, 23), hasItems("this"));
        // AutocompleteScopes.this, AutocompleteScopes.super
        // TODO this is not accessible
        // assertThat(insertText(file, 39, 32), not(hasItems("this")));
        // Super.this, Super.super
        // TODO this is not accessible
        // assertThat(insertText(file, 41, 19), not(hasItems("this")));
    }

    @Test
    public void staticSubInner() throws IOException {
        var file = "/org/javacs_server/example/AutocompleteScopes.java";

        // Static method
        var suggestions = insertText(file, 45, 22);

        // Locals
        assertThat(suggestions, hasItems("testLocalVariables", "testArguments"));
        // Static methods in enclosing scopes
        assertThat(suggestions, hasItems("testStatic"));
        assertThat(suggestions, hasItems("testOuterStaticMethod"));
        // Virtual methods in enclosing scopes
        assertThat(suggestions, hasItems("testInner"));
        assertThat(suggestions, hasItems("test"));
        // TODO this is not accessible
        // assertThat(suggestions, not(hasItems("testOuterMethods")));
        // Inherited static methods
        assertThat(suggestions, hasItems("testInheritedStaticMethod"));
        // Inherited virtual methods
        assertThat(suggestions, hasItems("testInheritedMethods"));
        // this/super in enclosing scopes
        assertThat(suggestions, hasItems("this"));
    }

    @Test
    public void staticSubInnerThisSuper() throws IOException {
        var file = "/org/javacs_server/example/AutocompleteScopes.java";

        // StaticSub.this, StaticSub.super
        assertThat(insertText(file, 67, 31), hasItems("this"));
        // AutocompleteScopes.this, AutocompleteScopes.super
        // TODO this is not accessible
        // assertThat(insertText(file, 69, 40), not(hasItems("this")));
        // Super.this, Super.super
        // TODO this is not accessible
        // assertThat(insertText(file, 71, 27), not(hasItems("this")));
    }

    @Test
    public void staticSubStaticMethod() throws IOException {
        var file = "/org/javacs_server/example/AutocompleteScopes.java";

        // Static method
        var suggestions = insertText(file, 78, 14);

        // Locals
        assertThat(suggestions, hasItems("testLocalVariables", "testArguments"));
        // Static methods in enclosing scopes
        assertThat(suggestions, hasItems("testStatic"));
        assertThat(suggestions, hasItems("testOuterStaticMethod"));
        // Virtual methods in enclosing scopes
        assertThat(suggestions, not(hasItems("testInner")));
        assertThat(suggestions, not(hasItems("test")));
        assertThat(suggestions, not(hasItems("testOuterMethods")));
        // Inherited static methods
        assertThat(suggestions, hasItems("testInheritedStaticMethod"));
        // Inherited virtual methods
        assertThat(suggestions, not(hasItems("testInheritedMethods")));
        // this/super in enclosing scopes
        // TODO this is not accessible
        // assertThat(suggestions, not(hasItems("this")));
    }

    // TODO this is not accessible
    @Ignore
    @Test
    public void staticSubStaticMethodThisSuper() throws IOException {
        var file = "/org/javacs_server/example/AutocompleteScopes.java";

        // StaticSub.this, StaticSub.super
        assertThat(insertText(file, 100, 23), not(hasItems("this")));
        // AutocompleteScopes.this, AutocompleteScopes.super
        assertThat(insertText(file, 102, 32), not(hasItems("this")));
        // Super.this, Super.super
        assertThat(insertText(file, 104, 19), not(hasItems("this")));
    }

    @Test
    public void staticSubStaticMethodInner() throws IOException {
        var file = "/org/javacs_server/example/AutocompleteScopes.java";

        // Static method
        var suggestions = insertText(file, 108, 22);

        // Locals
        assertThat(suggestions, hasItems("testLocalVariables", "testArguments"));
        // Static methods in enclosing scopes
        assertThat(suggestions, hasItems("testStatic"));
        assertThat(suggestions, hasItems("testOuterStaticMethod"));
        // Virtual methods in enclosing scopes
        assertThat(suggestions, hasItems("testInner"));
        // TODO this is not accessible
        // assertThat(suggestions, not(hasItems("test")));
        // TODO this is not accessible
        // assertThat(suggestions, not(hasItems("testOuterMethods")));
        // Inherited static methods
        assertThat(suggestions, hasItems("testInheritedStaticMethod"));
        // Inherited virtual methods
        // TODO this is not accessible
        // assertThat(suggestions, not(hasItems("testInheritedMethods")));
        // this/super in enclosing scopes
        assertThat(suggestions, hasItems("this"));
    }

    // TODO this is not accessible
    @Ignore
    @Test
    public void staticSubStaticMethodInnerThisSuper() throws IOException {
        var file = "/org/javacs_server/example/AutocompleteScopes.java";

        // StaticSub.this, StaticSub.super
        assertThat(insertText(file, 130, 31), not(hasItems("this")));
        // AutocompleteScopes.this, AutocompleteScopes.super
        assertThat(insertText(file, 132, 40), not(hasItems("this")));
        // Super.this, Super.super
        assertThat(insertText(file, 134, 27), not(hasItems("this")));
    }

    @Test
    public void sub() throws IOException {
        var file = "/org/javacs_server/example/AutocompleteScopes.java";

        // Static method
        var suggestions = insertText(file, 143, 14);

        // Locals
        assertThat(suggestions, hasItems("testLocalVariables", "testArguments"));
        // Static methods in enclosing scopes
        assertThat(suggestions, not(hasItems("testStatic")));
        assertThat(suggestions, hasItems("testOuterStaticMethod"));
        // Virtual methods in enclosing scopes
        assertThat(suggestions, not(hasItems("testInner")));
        assertThat(suggestions, hasItems("test"));
        assertThat(suggestions, hasItems("testOuterMethods"));
        // Inherited static methods
        assertThat(suggestions, hasItems("testInheritedStaticMethod"));
        // Inherited virtual methods
        assertThat(suggestions, hasItems("testInheritedMethods"));
        // this/super in enclosing scopes
        assertThat(suggestions, hasItems("this"));
    }

    @Test
    public void subThisSuper() throws IOException {
        var file = "/org/javacs_server/example/AutocompleteScopes.java";

        // sub.this, sub.super
        assertThat(insertText(file, 158, 17), hasItems("this"));
        // AutocompleteScopes.this, AutocompleteScopes.super
        assertThat(insertText(file, 160, 32), hasItems("this"));
        // Super.this, Super.super
        // TODO this is not accessible
        // assertThat(insertText(file, 162, 19), not(hasItems("this")));
    }

    @Test
    public void subInner() throws IOException {
        var file = "/org/javacs_server/example/AutocompleteScopes.java";

        // Static method
        var suggestions = insertText(file, 166, 22);

        // Locals
        assertThat(suggestions, hasItems("testLocalVariables", "testArguments"));
        // Static methods in enclosing scopes
        assertThat(suggestions, not(hasItems("testStatic")));
        assertThat(suggestions, hasItems("testOuterStaticMethod"));
        // Virtual methods in enclosing scopes
        assertThat(suggestions, hasItems("testInner"));
        assertThat(suggestions, hasItems("test"));
        assertThat(suggestions, hasItems("testOuterMethods"));
        // Inherited static methods
        assertThat(suggestions, hasItems("testInheritedStaticMethod"));
        // Inherited virtual methods
        assertThat(suggestions, hasItems("testInheritedMethods"));
        // this/super in enclosing scopes
        assertThat(suggestions, hasItems("this"));
    }

    @Test
    public void subInnerThisSuper() throws IOException {
        var file = "/org/javacs_server/example/AutocompleteScopes.java";

        // sub.this, sub.super
        assertThat(insertText(file, 181, 25), hasItems("this"));
        // AutocompleteScopes.this, AutocompleteScopes.super
        assertThat(insertText(file, 183, 40), hasItems("this"));
        // Super.this, Super.super
        // TODO this is not accessible
        // assertThat(insertText(file, 185, 27), not(hasItems("this")));
    }
}
