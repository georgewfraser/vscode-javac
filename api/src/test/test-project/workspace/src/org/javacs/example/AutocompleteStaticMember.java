package org.javacs_server.example;

public class AutocompleteStaticMember {
    public static void test() {
        AutocompleteStaticMember.test
    }

    private static String testFieldStatic;
    private String testField;
    private static String testMethodStatic() {
        return "foo";
    }
    private String testMethod() {
        return "foo";
    }
}