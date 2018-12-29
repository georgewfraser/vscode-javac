package org.javacs.lsp;

import java.util.List;

public class TextDocumentEdit {
    public VersionedTextDocumentIdentifier textDocument;
    public List<TextEdit> edits;
}
