package org.javacs_server;

import java.nio.file.*;

class Lib {
    static Path installRoot() {
        var root = Paths.get(".").toAbsolutePath();
        var p = root;
        while (p != null && !Files.exists(p.resolve("javaLsFlag.txt"))) p = p.getParent();
        if (p == null) throw new RuntimeException("Couldn't find javaLsFlag.txt in any parent of " + root);
        return p;
    }

    static final Path SRC_ZIP = installRoot().resolve("lib/src.zip");

    static final Path ERROR_PRONE = installRoot().resolve("lib/error_prone.jar");
}
