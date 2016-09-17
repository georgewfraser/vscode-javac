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
        Stream<String> cmdResult = Arrays.stream(cmds);

        if (OS.indexOf("win") != -1) {
            cmdResult = Arrays.stream(cmds)
                    .filter(c -> c.endsWith(".cmd") || c.endsWith(".bat"));
        }        

        Optional<File> cmd = cmdResult
            .map(c-> Arrays.stream(System.getenv("PATH")
                        .split(File.pathSeparator))
                        .map(p->new File(p, c))
                        .filter(p -> p.isFile() && p.canExecute())
            )
            .flatMap(c->c)
            .findFirst();

        if(cmd.isPresent())
            return cmd.get().getAbsolutePath();

        return null;
    }
}
