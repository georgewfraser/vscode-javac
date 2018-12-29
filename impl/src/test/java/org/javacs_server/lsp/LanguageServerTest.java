package org.javacs_server.lsp;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.javacs.lsp.InitializeParams;
import org.javacs.lsp.InitializeResult;
import org.javacs.lsp.LSP;
import org.javacs.lsp.LanguageClient;
import org.javacs.lsp.LanguageServer;
import org.javacs_server.Main;
import org.junit.Before;
import org.junit.Test;

public class LanguageServerTest {
    PipedInputStream clientToServer = new PipedInputStream(10 * 1024 * 1024),
            serverToClient = new PipedInputStream(10 * 1024 * 1024);
    PipedOutputStream writeClientToServer, writeServerToClient;
    LanguageServer mockServer;
    Thread main;
    CompletableFuture<Void> receivedInitialize = new CompletableFuture<>();

    class TestLanguageServer extends LanguageServer {
        @Override
        public InitializeResult initialize(InitializeParams params) {
            receivedInitialize.complete(null);
            return new InitializeResult();
        }
    }

    static {
        Main.setRootFormat();
    }

    @Before
    public void connectServerAndInitialize() throws IOException {
        writeClientToServer = new PipedOutputStream(clientToServer);
        writeServerToClient = new PipedOutputStream(serverToClient);
        main = new Thread(this::runServer, "runServer");
        main.start();
    }

    private void runServer() {
        LSP.connect(this::serverFactory, clientToServer, writeServerToClient);
    }

    private LanguageServer serverFactory(LanguageClient client) {
        mockServer = new TestLanguageServer();
        return mockServer;
    }

    private void sendToServer(String message) throws IOException {
        var header = String.format("Content-Length: %d\r\n\r\n", message.getBytes().length);
        writeClientToServer.write(header.getBytes());
        writeClientToServer.write(message.getBytes());
    }

    String initializeMessage = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
    String exitMessage = "{\"jsonrpc\":\"2.0\",\"method\":\"exit\"}";

    @Test
    public void exitMessageKillsServer()
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        // Send initialize message and wait for ack
        sendToServer(initializeMessage);
        receivedInitialize.get(10, TimeUnit.SECONDS);
        // Send exit message and wait for exit
        sendToServer(exitMessage);
        main.join(10_000);
        assertThat("Main thread has quit", main.isAlive(), equalTo(false));
    }

    @Test
    public void endOfStreamKillsServer()
            throws IOException, InterruptedException, ExecutionException, TimeoutException {
        // Send initialize message and wait for ack
        sendToServer(initializeMessage);
        receivedInitialize.get(10, TimeUnit.SECONDS);
        // Close stream
        writeClientToServer.close();
        clientToServer.close();
        // Wait for exit
        main.join(10_000);
        assertThat("Main thread has quit", main.isAlive(), equalTo(false));
    }
}
