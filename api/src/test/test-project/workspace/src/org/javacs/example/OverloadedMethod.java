package org.javacs_server.example;

class OverloadedMethod {
    void overloaded() { }
    void overloaded(int i) { }
    void overloaded(String s) { }

    void testCompletion() {
        over;
        java.util.List.of;
    }
}