package org.javacs_server.example;

import java.util.stream.Stream;




public class Bad {
    // This is a really evil input that causes the Attr phase to throw a null pointer exception
    public void test(Stream<?> stream) {
        stream.flatMap(compilationUnit -> {
            compilationUnit.accept(new Foo() {
                @Override
                public void callback() {
                    // Do nothing
                }
            });
        });
    }
}
