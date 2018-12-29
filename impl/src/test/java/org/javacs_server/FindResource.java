package org.javacs_server;

import java.net.URI;
import java.nio.file.Paths;

/** Find java sources in test-project/workspace/src */
public class FindResource {
    public static URI uri(String resourcePath) {
        if (resourcePath.startsWith("/")) resourcePath = resourcePath.substring(1);

        var path = Paths.get("./src/test/test-project/workspace/src").resolve(resourcePath).normalize();
        var file = path.toAbsolutePath().toFile();

        if (!file.exists()) throw new RuntimeException(file + " does not exist");

        return URI.create("file://" + file);
    }
}
