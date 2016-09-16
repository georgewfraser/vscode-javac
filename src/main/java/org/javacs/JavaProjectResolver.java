package org.javacs;
import java.nio.file.Path;
import java.util.Set;

public interface JavaProjectResolver  {
    JavaProjectResolver init(Path baseDirectory, Path projectFile);
    Set<Path> resolveClassPath();
    Set<Path> resolveSourcePath();
    Path resolveOutputPath();
}