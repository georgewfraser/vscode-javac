package org.javacs.config;

import java.util.*;
import java.nio.file.Path;
import com.google.gson.*;

public class Config {

  private Config () {};

  private static Set<String> externalDependencies(JsonObject settings) {
      if (!settings.has("externalDependencies")) return Set.of();
      var array = settings.getAsJsonArray("externalDependencies");
      var strings = new HashSet<String>();
      for (var each : array) {
          strings.add(each.getAsString());
      }
      return strings;
  }

  public static IConfig buildConfig(Path workspaceRoot, JsonObject settings) {
    String bazelBinary = "bazel";
    if (settings.has("bazelBinary")) { bazelBinary = settings.get("bazelBinary").getAsString(); }
    IConfig cfg = buildBazelConfig(workspaceRoot, bazelBinary);

    if (cfg == null) {
      // Falling back to InferConfig
      cfg = new InferConfig(workspaceRoot, externalDependencies(settings));
    }

    return cfg;
  }

  public static BazelConfig buildBazelConfig(Path workspaceRoot, String binary) {
    try {
      return BazelConfig.buildConfig(workspaceRoot, binary);
    } catch (Exception e) {
      return null;
    }
  }

}
