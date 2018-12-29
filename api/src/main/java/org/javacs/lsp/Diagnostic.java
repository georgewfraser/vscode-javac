package org.javacs.lsp;

public class Diagnostic {
    public Range range;
    public Integer severity;
    public String code, source, message;
}
