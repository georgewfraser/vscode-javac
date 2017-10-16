/*
 * Original work Copyright (c) 2017 George W Fraser.
 * Modified work Copyright (c) 2017 Palantir Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 */

package org.javacs;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JSR310Module;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;

public class Main {
    public static final ObjectMapper JSON =
            new ObjectMapper()
                    .registerModule(new Jdk8Module())
                    .registerModule(new JSR310Module())
                    .registerModule(pathAsJson())
                    .configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .configure(DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES, false);

    private static final Logger LOG = Logger.getLogger("main");

    public static void setRootFormat() {
        Logger root = Logger.getLogger("");

        for (Handler h : root.getHandlers()) h.setFormatter(new LogFormat());
    }

    private static SimpleModule pathAsJson() {
        SimpleModule m = new SimpleModule();

        m.addSerializer(
                Path.class,
                new JsonSerializer<Path>() {
                    @Override
                    public void serialize(
                            Path path, JsonGenerator gen, SerializerProvider serializerProvider)
                            throws IOException, JsonProcessingException {
                        gen.writeString(path.toString());
                    }
                });

        m.addDeserializer(
                Path.class,
                new JsonDeserializer<Path>() {
                    @Override
                    public Path deserialize(
                            JsonParser parse, DeserializationContext deserializationContext)
                            throws IOException, JsonProcessingException {
                        return Paths.get(parse.getText());
                    }
                });

        return m;
    }

    public static void main(String[] args) throws IOException {
        setRootFormat();

        try {
            Socket connection = connectToNode();

            run(connection);
        } catch (Throwable t) {
            LOG.log(Level.SEVERE, t.getMessage(), t);

            System.exit(1);
        }
    }

    private static Socket connectToNode() throws IOException {
        String port = System.getProperty("javacs.port");

        Objects.requireNonNull(port, "-Djavacs.port=? is required");

        LOG.info("Connecting to " + port);

        Socket socket = new Socket("localhost", Integer.parseInt(port));

        LOG.info("Connected to parent using socket on port " + port);

        return socket;
    }

    /**
     * Listen for requests from the parent node process. Send replies asynchronously. When the
     * request stream is closed, wait for 5s for all outstanding responses to compute, then return.
     */
    public static void run(Socket connection) throws IOException {
        JavaLanguageServer server = new JavaLanguageServer();
        Launcher<LanguageClient> launcher =
                LSPLauncher.createServerLauncher(
                        server, connection.getInputStream(), connection.getOutputStream());

        server.installClient(launcher.getRemoteProxy());
        launcher.startListening();

        LOG.info("Socket closed");
    }
}
