package org.javacs.resolver;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CommandFinder {
    private static String OS = System.getProperty("os.name").toLowerCase();
    private String[] cmds;

    CommandFinder(String... cmds) {
        this.cmds = cmds;
    }

    String findCommand() {
        Stream<String> path = Arrays.stream(System.getenv("PATH").split(File.pathSeparator));
        Stream<String> cmdResult;
        if (OS.indexOf("win") != -1) {
            cmdResult = Arrays.stream(cmds)
                    .filter(c -> c.endsWith(".cmd") || c.endsWith(".bat"));
        } else {
            cmdResult = Arrays.stream(cmds)
                    .filter(c -> !c.endsWith(".cmd") && !c.endsWith(".bat"));
        }
        List<String> cs = cmdResult.collect(Collectors.toList());
        for (String c : cs) {
            Optional<File> result =
                    path.map(p -> new File(p, c))
                            .filter(p -> p.isFile() && p.canExecute()).findFirst();
            if (result.isPresent()) {
                return result.get().getAbsolutePath();
            }
        }
        return null;
    }
}
