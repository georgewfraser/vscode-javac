package org.javacs_server.example;

public class AutocompleteMemberFixed {
    public void test() {
        this.field;
    }

    public static String fieldStatic;
    public String field;
    public static String methodStatic() {
        return "foo";
    }
    public String method() throws Exception {
        return "foo";
    }

    private static String fieldStaticPrivate;
    private String fieldPrivate;
    private static String methodStaticPrivate() {
        return "foo";
    }
    private String methodPrivate() {
        return "foo";
    }
}