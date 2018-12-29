package org.javacs_server.example;

class GotoEnum {
    void test() {
        System.out.println(Foos.Foo);
    }

    enum Foos {
        Foo,
        Bar
    }
}