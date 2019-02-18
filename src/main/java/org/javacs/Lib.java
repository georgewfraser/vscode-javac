package org.javacs;

import java.util.Optional;
import java.nio.file.*;

class Lib {
    static Optional<Path> installRoot() {
        var root = Paths.get(".").toAbsolutePath();
        var p = root;
        while (p != null && !Files.exists(p.resolve("javaLsFlag.txt"))) p = p.getParent();
        return Optional.ofNullable(p);
    }

    static final Optional<Path> SRC_ZIP = installRoot().map(path -> path.resolve("lib/src.zip"));
}
