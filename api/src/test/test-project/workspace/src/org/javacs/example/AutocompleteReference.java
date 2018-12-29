package org.javacs_server.example;

import java.util.function.Supplier;

public class AutocompleteReference {
    public void test() {
        print(this::)
    }

    private void print(Supplier<String> message) {
        System.out.println(message.get());
    }

    private static String testFieldStatic;
    private String testFields;
    private static String testMethodStatic() {
        return "foo";
    }
    private String testMethods() {
        return "foo";
    }
}