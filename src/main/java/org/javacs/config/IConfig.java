package org.javacs.config;

import java.util.Set;
import java.nio.file.Path;

public interface IConfig {
  public Set<Path> classpath();
  public Set<Path> sourcepath();
}
