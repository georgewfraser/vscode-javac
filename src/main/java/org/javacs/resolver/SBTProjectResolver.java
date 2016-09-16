package org.javacs.resolver;

import org.javacs.JavaProjectResolver;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class SBTProjectResolver implements JavaProjectResolver {
    private boolean initialize;
    private Set<Path> sourcePaths = new HashSet<>();
    private Set<Path> classPaths = new HashSet<>();

    public JavaProjectResolver init(Path baseDirectory, Path projectFile) {
        if (initialize)
            return this;

        File workingDirectory = projectFile.toAbsolutePath().getParent().toFile();
        String[] cmds = new String[]{
                new CommandFinder("sbt","sbt.bat","sbt.cmd").findCommand(),
                ";show sourceDirectories;test:sourceDirectories;printWarnings;show test:fullClasspath"
        };

        ProcessBuilder processBuilder = new ProcessBuilder(
                cmds
        );

        Process proc;
        try {
            proc = processBuilder
                    .directory(workingDirectory).start();
        } catch (IOException _e) {
            throw new RuntimeException("`" + Arrays.toString(cmds) + "` error ", _e);
        }

        int result;
        try {
            result = proc.waitFor();
        } catch (InterruptedException _e) {
            throw new RuntimeException("`" + Arrays.toString(cmds) + "` interrupted ", _e);
        }
        if (result != 0)
            throw new RuntimeException("`" + Arrays.toString(cmds) + "` returned " + result);

        //
        // the following processing order depends on the commands issued above
        // (first sourceDirectory, then classpath)
        //
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            String tmp;

            while ((tmp = reader.readLine()) != null) {
                if (tmp.contains("List")) {
                    String t = tmp.substring(tmp.indexOf("List("), tmp.lastIndexOf(")"));
                    sourcePaths.addAll(
                            Arrays.stream(t.split(","))
                                    .map(c -> c.replace("List(", "").replace(")", "").trim())
                                    .map(projectFile::resolve)
                                    .collect(Collectors.toSet()));
                }
                if (tmp.contains("success"))
                    break;
            }

            while ((tmp = reader.readLine()) != null) {
                if (tmp.contains("Attributed")) {
                    String t = tmp.substring(tmp.indexOf("Attributed("), tmp.lastIndexOf(")"));
                    classPaths.addAll(
                            Arrays.stream(t.split(","))
                                    .map(c -> c.replace("Attributed(", "").replace(")", "").trim())
                                    .map(projectFile::resolve)
                                    .collect(Collectors.toSet()));
                }
                if (tmp.contains("success"))
                    break;
            }
            initialize = true;
        } catch (IOException _e) {
            throw new RuntimeException(_e.getMessage(), _e);
        } finally {
            proc.destroy();
        }
        return this;
    }

    public Set<Path> resolveClassPath() {
        return Collections.unmodifiableSet(classPaths);
    }

    public Set<Path> resolveSourcePath() {
        return Collections.unmodifiableSet(sourcePaths);
    }

    public Path resolveOutputPath() {
        return Paths.get("target/javacs").toAbsolutePath();
    }
}
