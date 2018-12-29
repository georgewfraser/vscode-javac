package org.javacs.lsp;

public class CodeActionParams {
    public TextDocumentIdentifier textDocument;
    public Range range;
    public CodeActionContext context;
}
