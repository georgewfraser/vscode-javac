package org.javacs_server.example;

public class AutocompleteInners {
    public void testDeclaration() {
        AutocompleteInners.I;
        I;
    }

    public void testReference() {
        new AutocompleteInners.I;
        new Inner;
    }

    public void testEnum() {
        InnerEnum example = InnerEnum.F
    }

    public static class InnerClass {

    }

    public static enum InnerEnum {
        Foo,
        Bar
    }
}