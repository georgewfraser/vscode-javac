package org.javacs_server.example;

public class AutocompleteMembers {
    private String testFields;
    private static String testFieldStatic;

    {
        t; // testFields, testFieldStatic, testMethods, testMethodStatic
        this.t; // testFields, testMethods
        AutocompleteMembers.t; // testFieldStatic, testMethodStatic
        this::t; // testMethods
        AutocompleteMembers::t; // testMethods, testMethodStatic
    }

    static {
        t; // testFieldStatic
        AutocompleteMembers.t; // testFieldStatic
        AutocompleteMembers::t; // testMethods, testMethodStatic
    }

    private void testMethods(String testArguments) {
        t; // testFields, testFieldStatic, testMethods, testMethodStatic, testArguments
        this.t; // testFields, testMethods
        AutocompleteMembers.t; // testFieldStatic, testMethodStatic
        java.util.function.Supplier<String> test = this::t; // testMethods
        java.util.function.Supplier<String> test = AutocompleteMembers::t; // testMethods, testMethodStatic
    }

    private static void testMethodStatic(String testArguments) {
        t; // testFieldStatic, testArguments
        AutocompleteMembers.t; // testFieldStatic
        AutocompleteMembers::t; // testMethods, testMethodStatic
    }
}