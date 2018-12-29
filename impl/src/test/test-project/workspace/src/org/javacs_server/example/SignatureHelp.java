package org.javacs_server.example;

import java.util.concurrent.CompletableFuture; import java.util.ArrayList;

class SignatureHelp {
    void test(Runnable r) {
        CompletableFuture.runAsync();
        CompletableFuture.runAsync(r, );
        new SignatureHelp();
        new ArrayList<>(1);
    }

    /**
     * A constructor
     */
    SignatureHelp(String name) {
        // Nothing to do
    }
}