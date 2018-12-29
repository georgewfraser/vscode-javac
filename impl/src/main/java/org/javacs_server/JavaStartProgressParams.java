package org.javacs_server;

public class JavaStartProgressParams {
    private String message;

    public JavaStartProgressParams() {}

    public JavaStartProgressParams(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
