package org.javacs.lsp;

public class MarkedString {
    public String language, value;

    public MarkedString() {}

    public MarkedString(String language, String value) {
        this.language = language;
        this.value = value;
    }
}
