package org.javacs_server;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Stream;

class InferSourcePath {

    static Stream<Path> allJavaFiles(Path dir) {
        var match = FileSystems.getDefault().getPathMatcher("glob:*.java");

        try {
            return Files.walk(dir).filter(java -> match.matches(java.getFileName()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static Set<Path> sourcePath(Path workspaceRoot) {
        LOG.info("Searching for source roots in " + workspaceRoot);

        class SourcePaths implements Consumer<Path> {
            int certaintyThreshold = 10;
            Map<Path, Integer> sourceRoots = new HashMap<>();

            boolean alreadyKnown(Path java) {
                for (var root : sourceRoots.keySet()) {
                    if (java.startsWith(root) && sourceRoots.get(root) > certaintyThreshold) return true;
                }
                return false;
            }

            Optional<Path> infer(Path java) {
                var packageName = Objects.toString(Parser.parse(java).getPackageName(), "");
                var packagePath = packageName.replace('.', File.separatorChar);
                var dir = java.getParent();
                if (packagePath.isEmpty()) {
                    LOG.warning("Ignoring file with missing package declaration " + java);
                    return Optional.empty();
                } else if (!dir.endsWith(packagePath)) {
                    LOG.warning("Java source file " + java + " is not in " + packagePath);
                    return Optional.empty();
                } else {
                    var up = Paths.get(packagePath).getNameCount();
                    var truncate = dir;
                    for (int i = 0; i < up; i++) truncate = truncate.getParent();
                    return Optional.of(truncate);
                }
            }

            @Override
            public void accept(Path java) {
                if (java.getFileName().toString().equals("module-info.java")) return;

                if (!alreadyKnown(java)) {
                    infer(java)
                            .ifPresent(
                                    root -> {
                                        var count = sourceRoots.getOrDefault(root, 0);
                                        sourceRoots.put(root, count + 1);
                                    });
                }
            }
        }
        var checker = new SourcePaths();
        allJavaFiles(workspaceRoot).forEach(checker);
        return checker.sourceRoots.keySet();
    }

    private static final Logger LOG = Logger.getLogger("main");
}
