package org.javacs_server;

import com.google.gson.JsonElement;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.javacs.lsp.Diagnostic;
import org.javacs.lsp.InitializeParams;
import org.javacs.lsp.LanguageClient;
import org.javacs.lsp.PublishDiagnosticsParams;
import org.javacs.lsp.ShowMessageParams;
import org.javacs_server.lsp.*;

class LanguageServerFixture {

    public static Path DEFAULT_WORKSPACE_ROOT = Paths.get("src/test/test-project/workspace").toAbsolutePath();

    static {
        Main.setRootFormat();
    }

    static JavaLanguageServer getJavaLanguageServer() {
        return getJavaLanguageServer(DEFAULT_WORKSPACE_ROOT, diagnostic -> LOG.info(diagnostic.message));
    }

    static JavaLanguageServer getJavaLanguageServer(Path workspaceRoot, Consumer<Diagnostic> onError) {
        return getJavaLanguageServer(
                workspaceRoot,
                new LanguageClient() {
                    public void publishDiagnostics(PublishDiagnosticsParams params) {
                        params.diagnostics.forEach(onError);
                    }

                    public void showMessage(ShowMessageParams params) {}

                    public void registerCapability(String method, JsonElement options) {}

                    public void customNotification(String method, JsonElement params) {}
                });
    }

    private static JavaLanguageServer getJavaLanguageServer(Path workspaceRoot, LanguageClient client) {
        var server = new JavaLanguageServer(client);
        var init = new InitializeParams();

        init.rootUri = workspaceRoot.toUri();
        server.initialize(init);
        server.initialized();

        return server;
    }

    private static final Logger LOG = Logger.getLogger("main");
}
