package org.javacs;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

class JavaHomeHelper {
    static final Path NOT_FOUND = Paths.get("");

    static Path javaHome() {
        var fromEnv = System.getenv("JAVA_HOME");
        if (fromEnv != null) {
            return Paths.get(fromEnv);
        }
        var osName = System.getProperty("os.name");
        if (isWindows(osName)) {
            return windowsJavaHome();
        }
        if (isMac(osName)) {
            return macJavaHome();
        }
        if (isLinux(osName)) {
            return linuxJavaHome();
        }
        throw new RuntimeException("Unrecognized os.name " + osName);
    }

    private static Path windowsJavaHome() {
        for (var root : File.listRoots()) {
            var x64 = root.toPath().resolve("Program Files/Java").toString();
            var x86 = root.toPath().resolve("Program Files (x86)/Java").toString();
            var found = check(x64, x86);
            if (found != NOT_FOUND) return found;
        }
        return NOT_FOUND;
    }

    private static Path macJavaHome() {
        if (Files.isExecutable(Paths.get("/usr/libexec/java_home"))) {
            return execJavaHome();
        }
        String[] homes = {
            "/Library/Java/JavaVirtualMachines/Home",
            "/System/Library/Java/JavaVirtualMachines/Home",
            "/Library/Java/JavaVirtualMachines/Contents/Home",
            "/System/Library/Java/JavaVirtualMachines/Contents/Home",
        };
        return check(homes);
    }

    private static Path linuxJavaHome() {
        String[] homes = {
            "/usr/java", "/opt/java", "/usr/lib/jvm",
        };
        return check(homes);
    }

    private static Path execJavaHome() {
        try {
            var process = new ProcessBuilder().command("/usr/libexec/java_home").start();
            var out = new BufferedReader(new InputStreamReader(process.getInputStream()));
            var line = out.readLine();
            process.waitFor(5, TimeUnit.SECONDS);
            return Paths.get(line);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static Path check(String... roots) {
        for (var root : roots) {
            List<Path> list;
            try {
                list = Files.list(Paths.get(root)).collect(Collectors.toList());
            } catch (NoSuchFileException e) {
                continue;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            for (var jdk : list) {
                if (Files.exists(jdk.resolve("bin/javac")) || Files.exists(jdk.resolve("bin/javac.exe"))) {
                    return jdk;
                }
            }
        }
        return NOT_FOUND;
    }

    private static boolean isWindows(String osName) {
        return osName.toLowerCase().startsWith("windows");
    }

    private static boolean isMac(String osName) {
        return osName.toLowerCase().startsWith("mac");
    }

    private static boolean isLinux(String osName) {
        return osName.toLowerCase().startsWith("linux");
    }
}
