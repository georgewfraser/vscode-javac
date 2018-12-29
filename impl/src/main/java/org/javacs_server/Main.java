package org.javacs_server;

import java.util.logging.Level;
import java.util.logging.Logger;
import org.javacs.lsp.LSP;
import org.javacs.lsp.LanguageClient;

public class Main {
    private static final Logger LOG = Logger.getLogger("main");

    public static void setRootFormat() {
        var root = Logger.getLogger("");

        for (var h : root.getHandlers()) h.setFormatter(new LogFormat());
    }

    private JavaLanguageServer createServer(LanguageClient client) {
        return new JavaLanguageServer(client);
    }

    public static void main(String[] args) {
        try {
            // Logger.getLogger("").addHandler(new FileHandler("javacs_server.%u.log", false));
            setRootFormat();

            LSP.connect(JavaLanguageServer::new, System.in, System.out);
        } catch (Throwable t) {
            LOG.log(Level.SEVERE, t.getMessage(), t);

            System.exit(1);
        }
    }
}
