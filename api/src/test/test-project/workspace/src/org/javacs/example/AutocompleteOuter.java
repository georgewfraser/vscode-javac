package org.javacs_server.example;

public class AutocompleteOuter {
    public String testFields;
    public static String testFieldStatic;

    public String testMethods() { }
    public static String testMethodStatic() { }

    static class StaticInner {
        {
            t
        }
    }

    class Inner {
        {
            t
        }
    }
}