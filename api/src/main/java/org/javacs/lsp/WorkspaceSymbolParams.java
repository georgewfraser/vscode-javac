package org.javacs.lsp;

public class WorkspaceSymbolParams {
    public String query;

    public WorkspaceSymbolParams() {}

    public WorkspaceSymbolParams(String query) {
        this.query = query;
    }
}
