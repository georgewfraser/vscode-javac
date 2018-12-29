package org.javacs_server.example;

/**
 * A class
 */
public class AutocompleteDocstring {
    public void members() {
        this.;
    }

    public void statics() {
        AutocompleteDocstring.;
    }

    /**
     * A fieldStatic
     */
    public static String fieldStatic;
    /**
     * A fields
     */
    public String fields;
    /**
     * A methodStatic
     */
    public static String methodStatic() {
        return "foo";
    }
    /**
     * A methods
     */
    public String methods() {
        return "foo";
    }
}