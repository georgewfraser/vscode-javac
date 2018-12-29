package org.javacs_server.example;

import java.util.function.Supplier;

public class AutocompleteStaticReference {
    public static void test() {
        print(AutocompleteStaticReference::test)
    }

    private void print(Supplier<String> message) {
        System.out.println(message.get());
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