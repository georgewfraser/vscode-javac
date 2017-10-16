package org.javacs.example;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

public class SignatureHelp {
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
