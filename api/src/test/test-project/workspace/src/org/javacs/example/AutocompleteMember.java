package org.javacs_server.example;

public class AutocompleteMember {
    public void test() {
        this.;
    }

    public static String testFieldStatic;
    public String testFields;
    public static String testMethodStatic() {
        return "foo";
    }
    public String testMethods() throws Exception {
        return "foo";
    }

    private static String testFieldStaticPrivate;
    private String testFieldsPrivate;
    private static String testMethodStaticPrivate() {
        return "foo";
    }
    private String testMethodsPrivate() {
        return "foo";
    }
}