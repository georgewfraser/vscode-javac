package org.javacs.lsp;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import java.io.*;
import java.nio.charset.Charset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LSP {
    private static final Gson gson = new Gson();

    private static String readHeader(InputStream client) {
        var line = new StringBuilder();
        for (var next = read(client); true; next = read(client)) {
            if (next == '\r') {
                var last = read(client);
                assert last == '\n';
                break;
            }
            line.append(next);
        }
        return line.toString();
    }

    private static int parseHeader(String header) {
        var contentLength = "Content-Length: ";
        if (header.startsWith(contentLength)) {
            var tail = header.substring(contentLength.length());
            var length = Integer.parseInt(tail);
            return length;
        }
        return -1;
    }

    static class EndOfStream extends RuntimeException {}

    private static char read(InputStream client) {
        try {
            var c = client.read();
            if (c == -1) {
                LOG.warning("Stream from client has been closed, throwing kill exception...");
                throw new EndOfStream();
            }
            return (char) c;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String readLength(InputStream client, int byteLength) {
        // Eat whitespace
        // Have observed problems with extra \r\n sequences from VSCode
        var next = read(client);
        while (Character.isWhitespace(next)) {
            next = read(client);
        }
        // Append next
        var result = new StringBuilder();
        var i = 0;
        while (true) {
            result.append(next);
            i++;
            if (i == byteLength) break;
            next = read(client);
        }
        return result.toString();
    }

    static String nextToken(InputStream client) {
        var contentLength = -1;
        while (true) {
            var line = readHeader(client);
            // If header is empty, next line is the start of the message
            if (line.isEmpty()) return readLength(client, contentLength);
            // If header contains length, save it
            var maybeLength = parseHeader(line);
            if (maybeLength != -1) contentLength = maybeLength;
        }
    }

    static Message parseMessage(String token) {
        return gson.fromJson(token, Message.class);
    }

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private static void writeClient(OutputStream client, String messageText) {
        var messageBytes = messageText.getBytes(UTF_8);
        var headerText = String.format("Content-Length: %d\r\n\r\n", messageBytes.length);
        var headerBytes = headerText.getBytes(UTF_8);
        try {
            client.write(headerBytes);
            client.write(messageBytes);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static String toJson(Object message) {
        return gson.toJson(message);
    }

    static void respond(OutputStream client, int requestId, Object params) {
        if (params instanceof ResponseError) {
            throw new RuntimeException("Errors should be sent using LSP.error(...)");
        }
        if (params instanceof Optional) {
            var option = (Optional) params;
            params = option.orElse(null);
        }
        var jsonText = toJson(params);
        var messageText = String.format("{\"jsonrpc\":\"2.0\",\"id\":%d,\"result\":%s}", requestId, jsonText);
        writeClient(client, messageText);
    }

    static void error(OutputStream client, int requestId, ResponseError error) {
        var jsonText = toJson(error);
        var messageText = String.format("{\"jsonrpc\":\"2.0\",\"id\":%d,\"error\":%s}", requestId, jsonText);
        writeClient(client, messageText);
    }

    private static void notifyClient(OutputStream client, String method, Object params) {
        if (params instanceof Optional) {
            var option = (Optional) params;
            params = option.orElse(null);
        }
        var jsonText = toJson(params);
        var messageText = String.format("{\"jsonrpc\":\"2.0\",\"method\":\"%s\",\"params\":%s}", method, jsonText);
        writeClient(client, messageText);
    }

    private static class RealClient implements LanguageClient {
        final OutputStream send;

        RealClient(OutputStream send) {
            this.send = send;
        }

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams params) {
            notifyClient(send, "textDocument/publishDiagnostics", params);
        }

        @Override
        public void showMessage(ShowMessageParams params) {
            notifyClient(send, "window/showMessage", params);
        }

        @Override
        public void registerCapability(String method, JsonElement options) {
            var params = new RegistrationParams();
            params.id = UUID.randomUUID().toString();
            params.method = method;
            params.registerOptions = options;

            notifyClient(send, "client/registerCapability", params);
        }

        @Override
        public void customNotification(String method, JsonElement params) {
            notifyClient(send, method, params);
        }
    }

    public static void connect(
            Function<LanguageClient, LanguageServer> serverFactory, InputStream receive, OutputStream send) {
        var server = serverFactory.apply(new RealClient(send));
        var pending = new ArrayBlockingQueue<Message>(10);
        var endOfStream = new Message();

        // Read messages and process cancellations on a separate thread
        class MessageReader implements Runnable {
            void peek(Message message) {
                if (message.method.equals("$/cancelRequest")) {
                    var params = gson.fromJson(message.params, CancelParams.class);
                    var removed = pending.removeIf(r -> r.id != null && r.id.equals(params.id));
                    if (removed) LOG.info(String.format("Cancelled request %d, which had not yet started", params.id));
                    else LOG.info(String.format("Cannot cancel request %d because it has already started", params.id));
                }
            }

            private boolean kill() {
                LOG.info("Read stream has been closed, putting kill message onto queue...");
                try {
                    pending.put(endOfStream);
                    return true;
                } catch (Exception e) {
                    LOG.log(Level.SEVERE, "Failed to put kill message onto queue, will try again...", e);
                    return false;
                }
            }

            @Override
            public void run() {
                LOG.info("Placing incoming messages on queue...");

                while (true) {
                    try {
                        var token = nextToken(receive);
                        var message = parseMessage(token);
                        peek(message);
                        pending.put(message);
                    } catch (EndOfStream __) {
                        if (kill()) return;
                    } catch (Exception e) {
                        LOG.log(Level.SEVERE, e.getMessage(), e);
                    }
                }
            }
        }
        Thread reader = new Thread(new MessageReader(), "reader");
        reader.setDaemon(true);
        reader.start();

        // Process messages on main thread
        LOG.info("Reading messages from queue...");
        var hasAsyncWork = false;
        processMessages:
        while (true) {
            Message r;
            try {
                // Take a break periodically
                r = pending.poll(200, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                LOG.log(Level.SEVERE, e.getMessage(), e);
                continue;
            }
            // If receive has been closed, exit
            if (r == endOfStream) {
                LOG.warning("Stream from client has been closed, exiting...");
                break processMessages;
            }
            // If poll(_) failed, loop again
            if (r == null) {
                if (hasAsyncWork) {
                    server.doAsyncWork();
                    hasAsyncWork = false;
                }
                continue;
            }
            // Otherwise, process the new message
            hasAsyncWork = true;
            try {
                switch (r.method) {
                    case "initialize":
                        {
                            var params = gson.fromJson(r.params, InitializeParams.class);
                            var response = server.initialize(params);
                            respond(send, r.id, response);
                            break;
                        }
                    case "initialized":
                        {
                            server.initialized();
                            break;
                        }
                    case "shutdown":
                        {
                            LOG.warning("Got shutdown message");
                            respond(send, r.id, null);
                            break;
                        }
                    case "exit":
                        {
                            LOG.warning("Got exit message, exiting...");
                            break processMessages;
                        }
                    case "workspace/didChangeWorkspaceFolders":
                        {
                            var params = gson.fromJson(r.params, DidChangeWorkspaceFoldersParams.class);
                            server.didChangeWorkspaceFolders(params);
                            break;
                        }
                    case "workspace/didChangeConfiguration":
                        {
                            var params = gson.fromJson(r.params, DidChangeConfigurationParams.class);
                            server.didChangeConfiguration(params);
                            break;
                        }
                    case "workspace/didChangeWatchedFiles":
                        {
                            var params = gson.fromJson(r.params, DidChangeWatchedFilesParams.class);
                            server.didChangeWatchedFiles(params);
                            break;
                        }
                    case "workspace/symbol":
                        {
                            var params = gson.fromJson(r.params, WorkspaceSymbolParams.class);
                            var response = server.workspaceSymbols(params);
                            respond(send, r.id, response);
                            break;
                        }
                    case "textDocument/documentLink":
                        {
                            var params = gson.fromJson(r.params, DocumentLinkParams.class);
                            var response = server.documentLink(params);
                            respond(send, r.id, response);
                            break;
                        }
                    case "textDocument/didOpen":
                        {
                            var params = gson.fromJson(r.params, DidOpenTextDocumentParams.class);
                            server.didOpenTextDocument(params);
                            break;
                        }
                    case "textDocument/didChange":
                        {
                            var params = gson.fromJson(r.params, DidChangeTextDocumentParams.class);
                            server.didChangeTextDocument(params);
                            break;
                        }
                    case "textDocument/willSave":
                        {
                            var params = gson.fromJson(r.params, WillSaveTextDocumentParams.class);
                            server.willSaveTextDocument(params);
                            break;
                        }
                    case "textDocument/willSaveWaitUntil":
                        {
                            var params = gson.fromJson(r.params, WillSaveTextDocumentParams.class);
                            var response = server.willSaveWaitUntilTextDocument(params);
                            respond(send, r.id, response);
                            break;
                        }
                    case "textDocument/didSave":
                        {
                            var params = gson.fromJson(r.params, DidSaveTextDocumentParams.class);
                            server.didSaveTextDocument(params);
                            break;
                        }
                    case "textDocument/didClose":
                        {
                            var params = gson.fromJson(r.params, DidCloseTextDocumentParams.class);
                            server.didCloseTextDocument(params);
                            break;
                        }
                    case "textDocument/completion":
                        {
                            var params = gson.fromJson(r.params, TextDocumentPositionParams.class);
                            var response = server.completion(params);
                            respond(send, r.id, response);
                            break;
                        }
                    case "completionItem/resolve":
                        {
                            var params = gson.fromJson(r.params, CompletionItem.class);
                            var response = server.resolveCompletionItem(params);
                            respond(send, r.id, response);
                            break;
                        }
                    case "textDocument/hover":
                        {
                            var params = gson.fromJson(r.params, TextDocumentPositionParams.class);
                            var response = server.hover(params);
                            respond(send, r.id, response);
                            break;
                        }
                    case "textDocument/signatureHelp":
                        {
                            var params = gson.fromJson(r.params, TextDocumentPositionParams.class);
                            var response = server.signatureHelp(params);
                            respond(send, r.id, response);
                            break;
                        }
                    case "textDocument/definition":
                        {
                            var params = gson.fromJson(r.params, TextDocumentPositionParams.class);
                            var response = server.gotoDefinition(params);
                            respond(send, r.id, response);
                            break;
                        }
                    case "textDocument/references":
                        {
                            var params = gson.fromJson(r.params, ReferenceParams.class);
                            var response = server.findReferences(params);
                            respond(send, r.id, response);
                            break;
                        }
                    case "textDocument/documentSymbol":
                        {
                            var params = gson.fromJson(r.params, DocumentSymbolParams.class);
                            var response = server.documentSymbol(params);
                            respond(send, r.id, response);
                            break;
                        }
                    case "textDocument/codeAction":
                        {
                            var params = gson.fromJson(r.params, CodeActionParams.class);
                            var response = server.codeAction(params);
                            respond(send, r.id, response);
                            break;
                        }
                    case "textDocument/codeLens":
                        {
                            var params = gson.fromJson(r.params, CodeLensParams.class);
                            var response = server.codeLens(params);
                            respond(send, r.id, response);
                            break;
                        }
                    case "codeLens/resolve":
                        {
                            var params = gson.fromJson(r.params, CodeLens.class);
                            var response = server.resolveCodeLens(params);
                            respond(send, r.id, response);
                            break;
                        }
                    case "textDocument/prepareRename":
                        {
                            var params = gson.fromJson(r.params, TextDocumentPositionParams.class);
                            var response = server.prepareRename(params);
                            respond(send, r.id, response);
                            break;
                        }
                    case "textDocument/rename":
                        {
                            var params = gson.fromJson(r.params, RenameParams.class);
                            var response = server.rename(params);
                            respond(send, r.id, response);
                            break;
                        }
                    case "textDocument/formatting":
                        {
                            var params = gson.fromJson(r.params, DocumentFormattingParams.class);
                            var response = server.formatting(params);
                            respond(send, r.id, response);
                            break;
                        }
                    case "textDocument/foldingRange":
                        {
                            var params = gson.fromJson(r.params, FoldingRangeParams.class);
                            var response = server.foldingRange(params);
                            respond(send, r.id, response);
                            break;
                        }
                    case "$/cancelRequest":
                        // Already handled in peek(message)
                        break;
                    default:
                        LOG.warning(String.format("Don't know what to do with method `%s`", r.method));
                }
            } catch (Exception e) {
                LOG.log(Level.SEVERE, e.getMessage(), e);
                if (r.id != null) {
                    error(send, r.id, new ResponseError(ErrorCodes.InternalError, e.getMessage(), null));
                }
            }
        }
    }

    private static final Logger LOG = Logger.getLogger("main");
}
