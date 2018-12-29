package org.javacs_server.example;

import java.util.Collections;
import java.util.List;

public class CompileTwice {
    public List<String> message() {
        return Collections.singletonList("Hi again!");
    }

    public static class NestedStaticClass {

    }

    public class NestedClass {

    }

    private Object anonymousClass = new Object() {

    };
}