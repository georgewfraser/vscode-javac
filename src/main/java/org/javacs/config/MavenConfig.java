package org.javacs.config;

import com.google.devtools.build.lib.analysis.AnalysisProtos;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class MavenConfig implements IConfig {
  private static final Logger LOG = Logger.getLogger("main");
  private static final Path NOT_FOUND = Paths.get("");
  private static final Pattern DEPENDENCY =
    Pattern.compile("^\\[INFO\\]\\s+(.*:.*:.*:.*:.*):(/.*?)( -- module .*)?$");

  private final Path mvnHome;
  private final Path pomXml;
  private final String binary;

  private MavenConfig(Path mvnHome, Path pomXml, String binary) {
    this.mvnHome = mvnHome;
    this.pomXml = pomXml;
    this.binary = binary;
  }

  private static Path defaultMavenHome() {
    return Paths.get(System.getProperty("user.home")).resolve(".m2");
  }

  public static MavenConfig buildConfig(Path current) {
    return MavenConfig.buildConfig(current, defaultMavenHome());
  }

  public static MavenConfig buildConfig(Path current, Path mvnHome) {
    var pomXml = current.resolve("pom.xml");
    if (Files.exists(pomXml)) {
      return new MavenConfig(mvnHome, pomXml, getMvnCommand());
    }

    return null;
  }

  private static String findExecutableOnPath(String name) {
      for (var dirname : System.getenv("PATH").split(File.pathSeparator)) {
          var file = new File(dirname, name);
          if (file.isFile() && file.canExecute()) {
              return file.getAbsolutePath();
          }
      }
      return null;
  }

  static String getMvnCommand() {
    var mvnCommand = "mvn";
    if (File.separatorChar == '\\') {
      mvnCommand = findExecutableOnPath("mvn.cmd");
      if (mvnCommand == null) {
        mvnCommand = findExecutableOnPath("mvn.bat");
      }
    }
    return mvnCommand;
  }

  private Set<Path> mvnDependencies(String goal) {
    try {
      // TODO consider using mvn valide dependency:copy-dependencies -DoutputDirectory=??? instead
      // Run maven as a subprocess
      String[] command = {
        binary,
        "--batch-mode", // Turns off ANSI control sequences
        "validate",
        goal,
        "-DincludeScope=test",
        "-DoutputAbsoluteArtifactFilename=true",
      };
      var output = Files.createTempFile("java-language-server-maven-output", ".txt");
      LOG.info("Running " + String.join(" ", command) + " ...");
      var workingDirectory = pomXml.toAbsolutePath().getParent().toFile();
      var process =
        new ProcessBuilder()
        .command(command)
        .directory(workingDirectory)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .redirectOutput(output.toFile())
        .start();
      // Wait for process to exit
      var result = process.waitFor();
      if (result != 0) {
        LOG.severe("`" + String.join(" ", command) + "` returned " + result);
        return Set.of();
      }
      // Read output
      var dependencies = new HashSet<Path>();
      for (var line : Files.readAllLines(output)) {
        var jar = readDependency(line);
        if (jar != NOT_FOUND) {
          dependencies.add(jar);
        }
      }
      return dependencies;
    } catch (InterruptedException | IOException e) {
      throw new RuntimeException(e);
    }
  }

  private Path readDependency(String line) {
    var match = DEPENDENCY.matcher(line);
    if (!match.matches()) {
      return NOT_FOUND;
    }
    var artifact = match.group(1);
    var path = match.group(2);
    LOG.info(String.format("...%s => %s", artifact, path));
    return Paths.get(path);
  }

  public Set<Path> classpath(){
    return mvnDependencies("dependency:list");
  }

  public Set<Path> sourcepath() {
    return mvnDependencies("dependency:sources");
  }

}
