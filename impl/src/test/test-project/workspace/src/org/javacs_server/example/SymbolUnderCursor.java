package org.javacs_server.example;

public class SymbolUnderCursor {
    public String field;

    public String method(String methodParameter) {
        String localVariable;

        localVariable = field;
        localVariable = methodParameter;

        method(SymbolUnderCursor.class.getName());
        this.method("foo");
        java.util.function.Function<String, String> m = this::method;
    }

    public SymbolUnderCursor(String constructorParameter) {
        
    }

    @Override
    public void overrideMethod() {

    }
}