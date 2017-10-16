package org.javacs.example;

public class GotoEnum {
    void test() {
        System.out.println(Foos.Foo);
    }

    enum Foos {
        Foo,
        Bar
    }
}
