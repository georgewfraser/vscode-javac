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


public class BazelConfig implements IConfig {
  private static final Logger LOG = Logger.getLogger("main");
  private static final Path NOT_FOUND = Paths.get("");

  private String binary;
  private Path workspaceRoot;

  // Constructors
  private BazelConfig(String binary, Path root) {
    this.binary = binary;
    this.workspaceRoot = root;
  }

  public static BazelConfig buildConfig(Path current){
    return BazelConfig.buildConfig(current, "bazel");
  }

  public static BazelConfig buildConfig(Path root, String binary){

    Path workspace = root;
    for (var current = root; current != null; current = current.getParent()) {
      if (Files.exists(current.resolve("WORKSPACE"))) {
        workspace = current;
        break;
      }
    }

    return new BazelConfig(binary, workspace);
  }

  public Set<Path> classpath() {
    var absolute = new HashSet<Path>();
    for (var relative : aQuery("Javac", "--classpath")) {
      absolute.add(workspaceRoot.resolve(relative));
    }
    return absolute;
  }

  public Set<Path> sourcepath() {
    var absolute = new HashSet<Path>();
    var base = outputBase();
    for (var relative : aQuery("JavaSourceJar", "--sources")) {
      absolute.add(base.resolve(relative));
    }
    return absolute;
  }

  private Path outputBase() {
    // Run bazel as a subprocess
    String[] command = { binary, "info", "output_base" };
    var output = fork(command);
    if (output == NOT_FOUND) {
      return NOT_FOUND;
    }
    // Read output
    try {
      var out = Files.readString(output).trim();
      return Paths.get(out);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private Set<String> aQuery(String filterMnemonic, String filterArgument) {
    String[] command = {
      binary,
      "aquery",
      "--output=proto",
      "mnemonic("
        + filterMnemonic
        + ", kind(java_library, ...) union kind(java_test, ...) union kind(java_binary, ...))"
    };
    var output = fork(command);
    if (output == NOT_FOUND) {
      return Set.of();
    }
    return readActionGraph(output, filterArgument);
  }

  private Path fork(String[] command) {
    try {
      LOG.info("Running " + String.join(" ", command) + " ...");
      var output = Files.createTempFile("java-language-server-bazel-output", ".proto");
      var process =
        new ProcessBuilder()
        .command(command)
        .directory(workspaceRoot.toFile())
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .redirectOutput(output.toFile())
        .start();
      // Wait for process to exit
      var result = process.waitFor();
      if (result != 0) {
        LOG.warning("`" + String.join(" ", command) + "` returned " + result);
      }
      return output;
    } catch (InterruptedException | IOException e) {
      throw new RuntimeException(e);
    }
  }

  private Set<String> readActionGraph(Path output, String filterArgument) {
    try {
      var container = AnalysisProtos.ActionGraphContainer.parseFrom(Files.newInputStream(output));
      var argumentPaths = new HashSet<String>();
      var outputIds = new HashSet<String>();
      for (var action : container.getActionsList()) {
        var isFilterArgument = false;
        for (var argument : action.getArgumentsList()) {
          if (isFilterArgument && argument.startsWith("-")) {
            isFilterArgument = false;
            continue;
          }
          if (!isFilterArgument) {
            isFilterArgument = argument.equals(filterArgument);
            continue;
          }
          argumentPaths.add(argument);
        }
        outputIds.addAll(action.getOutputIdsList());
      }
      var artifactPaths = new HashSet<String>();
      for (var artifact : container.getArtifactsList()) {
        if (!argumentPaths.contains(artifact.getExecPath())) {
          // artifact was not specified by --filterArgument
          continue;
        }
        if (outputIds.contains(artifact.getId())) {
          // artifact is the output of another java action
          continue;
        }
        var relative = artifact.getExecPath();
        LOG.info("...found bazel dependency " + relative);
        artifactPaths.add(relative);
      }
      return artifactPaths;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }


}
