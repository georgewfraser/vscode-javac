package org.javacs.lsp;

import java.util.List;

public class DidChangeTextDocumentParams {
    public VersionedTextDocumentIdentifier textDocument;
    public List<TextDocumentContentChangeEvent> contentChanges;
}
