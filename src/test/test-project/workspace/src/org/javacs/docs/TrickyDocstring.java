package org.javacs.docs;

class TrickyDocstring {

    void test() {
        example("foo", new String[] { "foo" }, null);
        parameterized("foo");
        new SubDoc().method();
        new SubDoc().interfaceMethod();
    }

    /**
     * Docstring!
     */
    void example(String foo, String[] array, List<String> list) {

    }

    /**
     * Another docstring!
     */
    <T> void parameterized(T foo) {

    }
}
