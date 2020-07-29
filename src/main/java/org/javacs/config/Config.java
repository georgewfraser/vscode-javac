package org.javacs.config;

import java.util.*;
import java.nio.file.Path;

public class Config {

  private Config () {};

  public static IConfig buildConfig(Path workspaceRoot, Set<String> externalDependencies) {
    return new InferConfig(workspaceRoot, externalDependencies);
  }

  public static BazelConfig buildBazelConfig(Path workspaceRoot, String binary) {
    try {
      return BazelConfig.buildConfig(workspaceRoot, binary);
    } catch (Exception e) {
      return null;
    }
  }

}
