package org.javacs.lsp;

public class RenameParams {
    public TextDocumentIdentifier textDocument;
    public Position position;
    public String newName;
}
