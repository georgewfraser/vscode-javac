/*
 * Original work Copyright (c) 2017 George W Fraser.
 * Modified work Copyright (c) 2017 Palantir Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 */

package org.javacs;

import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.util.Set;
import org.junit.Test;

public class CompletionsScopesTest extends CompletionsBase {
    @Test
    public void staticSub() throws IOException {
        String file = "/org/javacs/example/AutocompleteScopes.java";

        // Static method
        Set<String> suggestions = insertText(file, 15, 14);

        // Locals
        assertThat(suggestions, hasItems("localVariables", "arguments"));
        // Static methods in enclosing scopes
        assertThat(suggestions, hasItems("testStatic"));
        assertThat(suggestions, hasItems("outerStaticMethod"));
        // Virtual methods in enclosing scopes
        assertThat(suggestions, not(hasItems("testInner")));
        assertThat(suggestions, hasItems("test"));
        assertThat(suggestions, not(hasItems("outerMethods")));
        // Inherited static methods
        assertThat(suggestions, hasItems("inheritedStaticMethod"));
        // Inherited virtual methods
        assertThat(suggestions, hasItems("inheritedMethods"));
        // this/super in enclosing scopes
        assertThat(suggestions, hasItems("this", "super"));
    }

    @Test
    public void staticSubThisSuper() throws IOException {
        String file = "/org/javacs/example/AutocompleteScopes.java";

        // StaticSub.this, StaticSub.super
        assertThat(insertText(file, 37, 23), hasItems("this", "super"));
        // AutocompleteScopes.this, AutocompleteScopes.super
        assertThat(insertText(file, 39, 32), not(hasItems("this", "super")));
        // Super.this, Super.super
        assertThat(insertText(file, 41, 19), not(hasItems("this", "super")));
    }

    @Test
    public void staticSubInner() throws IOException {
        String file = "/org/javacs/example/AutocompleteScopes.java";

        // Static method
        Set<String> suggestions = insertText(file, 45, 22);

        // Locals
        assertThat(suggestions, hasItems("localVariables", "arguments"));
        // Static methods in enclosing scopes
        assertThat(suggestions, hasItems("testStatic"));
        assertThat(suggestions, hasItems("outerStaticMethod"));
        // Virtual methods in enclosing scopes
        assertThat(suggestions, hasItems("testInner"));
        assertThat(suggestions, hasItems("test"));
        assertThat(suggestions, not(hasItems("outerMethods")));
        // Inherited static methods
        assertThat(suggestions, hasItems("inheritedStaticMethod"));
        // Inherited virtual methods
        assertThat(suggestions, hasItems("inheritedMethods"));
        // this/super in enclosing scopes
        assertThat(suggestions, hasItems("this", "super"));
    }

    @Test
    public void staticSubInnerThisSuper() throws IOException {
        String file = "/org/javacs/example/AutocompleteScopes.java";

        // StaticSub.this, StaticSub.super
        assertThat(insertText(file, 67, 31), hasItems("this", "super"));
        // AutocompleteScopes.this, AutocompleteScopes.super
        assertThat(insertText(file, 69, 40), not(hasItems("this", "super")));
        // Super.this, Super.super
        assertThat(insertText(file, 71, 27), not(hasItems("this", "super")));
    }

    @Test
    public void staticSubStaticMethod() throws IOException {
        String file = "/org/javacs/example/AutocompleteScopes.java";

        // Static method
        Set<String> suggestions = insertText(file, 78, 14);

        // Locals
        assertThat(suggestions, hasItems("localVariables", "arguments"));
        // Static methods in enclosing scopes
        assertThat(suggestions, hasItems("testStatic"));
        assertThat(suggestions, hasItems("outerStaticMethod"));
        // Virtual methods in enclosing scopes
        assertThat(suggestions, not(hasItems("testInner")));
        assertThat(suggestions, not(hasItems("test")));
        assertThat(suggestions, not(hasItems("outerMethods")));
        // Inherited static methods
        assertThat(suggestions, hasItems("inheritedStaticMethod"));
        // Inherited virtual methods
        assertThat(suggestions, not(hasItems("inheritedMethods")));
        // this/super in enclosing scopes
        assertThat(suggestions, not(hasItems("this", "super")));
    }

    @Test
    public void staticSubStaticMethodThisSuper() throws IOException {
        String file = "/org/javacs/example/AutocompleteScopes.java";

        // StaticSub.this, StaticSub.super
        assertThat(insertText(file, 100, 23), not(hasItems("this", "super")));
        // AutocompleteScopes.this, AutocompleteScopes.super
        assertThat(insertText(file, 102, 32), not(hasItems("this", "super")));
        // Super.this, Super.super
        assertThat(insertText(file, 104, 19), not(hasItems("this", "super")));
    }

    @Test
    public void staticSubStaticMethodInner() throws IOException {
        String file = "/org/javacs/example/AutocompleteScopes.java";

        // Static method
        Set<String> suggestions = insertText(file, 108, 22);

        // Locals
        assertThat(suggestions, hasItems("localVariables", "arguments"));
        // Static methods in enclosing scopes
        assertThat(suggestions, hasItems("testStatic"));
        assertThat(suggestions, hasItems("outerStaticMethod"));
        // Virtual methods in enclosing scopes
        assertThat(suggestions, hasItems("testInner"));
        assertThat(suggestions, not(hasItems("test")));
        assertThat(suggestions, not(hasItems("outerMethods")));
        // Inherited static methods
        assertThat(suggestions, hasItems("inheritedStaticMethod"));
        // Inherited virtual methods
        assertThat(suggestions, not(hasItems("inheritedMethods")));
        // this/super in enclosing scopes
        assertThat(suggestions, hasItems("this", "super"));
    }

    @Test
    public void staticSubStaticMethodInnerThisSuper() throws IOException {
        String file = "/org/javacs/example/AutocompleteScopes.java";

        // StaticSub.this, StaticSub.super
        assertThat(insertText(file, 130, 31), not(hasItems("this", "super")));
        // AutocompleteScopes.this, AutocompleteScopes.super
        assertThat(insertText(file, 132, 40), not(hasItems("this", "super")));
        // Super.this, Super.super
        assertThat(insertText(file, 134, 27), not(hasItems("this", "super")));
    }

    @Test
    public void sub() throws IOException {
        String file = "/org/javacs/example/AutocompleteScopes.java";

        // Static method
        Set<String> suggestions = insertText(file, 143, 14);

        // Locals
        assertThat(suggestions, hasItems("localVariables", "arguments"));
        // Static methods in enclosing scopes
        assertThat(suggestions, not(hasItems("testStatic")));
        assertThat(suggestions, hasItems("outerStaticMethod"));
        // Virtual methods in enclosing scopes
        assertThat(suggestions, not(hasItems("testInner")));
        assertThat(suggestions, hasItems("test"));
        assertThat(suggestions, hasItems("outerMethods"));
        // Inherited static methods
        assertThat(suggestions, hasItems("inheritedStaticMethod"));
        // Inherited virtual methods
        assertThat(suggestions, hasItems("inheritedMethods"));
        // this/super in enclosing scopes
        assertThat(suggestions, hasItems("this", "super"));
    }

    @Test
    public void subThisSuper() throws IOException {
        String file = "/org/javacs/example/AutocompleteScopes.java";

        // sub.this, sub.super
        assertThat(insertText(file, 158, 17), hasItems("this", "super"));
        // AutocompleteScopes.this, AutocompleteScopes.super
        assertThat(insertText(file, 160, 32), hasItems("this", "super"));
        // Super.this, Super.super
        assertThat(insertText(file, 162, 19), not(hasItems("this", "super")));
    }

    @Test
    public void subInner() throws IOException {
        String file = "/org/javacs/example/AutocompleteScopes.java";

        // Static method
        Set<String> suggestions = insertText(file, 166, 22);

        // Locals
        assertThat(suggestions, hasItems("localVariables", "arguments"));
        // Static methods in enclosing scopes
        assertThat(suggestions, not(hasItems("testStatic")));
        assertThat(suggestions, hasItems("outerStaticMethod"));
        // Virtual methods in enclosing scopes
        assertThat(suggestions, hasItems("testInner"));
        assertThat(suggestions, hasItems("test"));
        assertThat(suggestions, hasItems("outerMethods"));
        // Inherited static methods
        assertThat(suggestions, hasItems("inheritedStaticMethod"));
        // Inherited virtual methods
        assertThat(suggestions, hasItems("inheritedMethods"));
        // this/super in enclosing scopes
        assertThat(suggestions, hasItems("this", "super"));
    }

    @Test
    public void subInnerThisSuper() throws IOException {
        String file = "/org/javacs/example/AutocompleteScopes.java";

        // sub.this, sub.super
        assertThat(insertText(file, 181, 25), hasItems("this", "super"));
        // AutocompleteScopes.this, AutocompleteScopes.super
        assertThat(insertText(file, 183, 40), hasItems("this", "super"));
        // Super.this, Super.super
        assertThat(insertText(file, 185, 27), not(hasItems("this", "super")));
    }
}
