package org.javacs.lsp;

import java.net.URI;
import java.util.List;

public class PublishDiagnosticsParams {
    public URI uri;
    public List<Diagnostic> diagnostics;

    public PublishDiagnosticsParams() {}

    public PublishDiagnosticsParams(URI uri, List<Diagnostic> diagnostics) {
        this.uri = uri;
        this.diagnostics = diagnostics;
    }
}
