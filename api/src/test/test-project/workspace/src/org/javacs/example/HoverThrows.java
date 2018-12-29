package org.javacs_server.example;

import java.io.IOException;

class HoverThrows {
    void foo() throws IOException {
        throw new IOException();
    }

    void bar() {
        foo();
    }
}