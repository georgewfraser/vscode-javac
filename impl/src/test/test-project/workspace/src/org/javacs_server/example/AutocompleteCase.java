package org.javacs_server.example;

class AutocompleteCase {
    void test() {
        switch (myEnum()) {
            case 
        }
    }

    MyEnum myEnum() {
        return MyEnum.Foo;
    }

    enum MyEnum {
        Foo,
        Bar
    }
}