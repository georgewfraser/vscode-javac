package org.javacs.lsp;

import java.net.URI;
import java.util.List;
import java.util.Map;

public class WorkspaceEdit {
    public Map<URI, List<TextEdit>> changes;
}
