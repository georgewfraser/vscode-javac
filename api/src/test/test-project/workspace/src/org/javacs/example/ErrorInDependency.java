package org.javacs_server.example;

public class ErrorInDependency {
    public String test() {
        return (new UndefinedSymbol()).test();
    }
}