package org.javacs.lsp;

public class DidOpenTextDocumentParams {
    public TextDocumentItem textDocument;

    public DidOpenTextDocumentParams() {}

    public DidOpenTextDocumentParams(TextDocumentItem textDocument) {
        this.textDocument = textDocument;
    }
}
