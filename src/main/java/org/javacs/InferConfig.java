package org.javacs;

import com.google.common.base.Joiner;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class InferConfig {
    /** Root of the workspace that is currently open in VSCode */
    private final Path workspaceRoot;
    /** User-specified external dependencies, configured with java.externalDependencies */
    private final Collection<Artifact> externalDependencies;
    /** User-specified class path, configured with java.classPath */
    private final List<Path> classPath;
    /** Location of the maven repository, usually ~/.m2 */
    private final Path mavenHome;
    /** Location of the gradle cache, usually ~/.gradle */
    private final Path gradleHome;

    InferConfig(
            Path workspaceRoot,
            Collection<Artifact> externalDependencies,
            List<Path> classPath,
            Path mavenHome,
            Path gradleHome) {
        this.workspaceRoot = workspaceRoot;
        this.externalDependencies = externalDependencies;
        this.classPath = classPath;
        this.mavenHome = mavenHome;
        this.gradleHome = gradleHome;
    }

    // TODO move to JavaLanguageServer
    static Stream<Path> allJavaFiles(Path dir) {
        PathMatcher match = FileSystems.getDefault().getPathMatcher("glob:*.java");

        try {
            // TODO instead of looking at EVERY file, once you see a few files with the same source directory,
            // ignore all subsequent files in the directory
            return Files.walk(dir).filter(java -> match.matches(java.getFileName()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static Instant buildFilesModified(Path workspaceRoot) {
        Instant workspaceModified = fileModified(workspaceRoot.resolve("WORKSPACE")),
                pomModified = fileModified(workspaceRoot.resolve("pom.xml"));

        return maxTime(workspaceModified, pomModified);
    }

    private static Instant fileModified(Path file) {
        if (Files.exists(file)) {
            try {
                return Files.getLastModifiedTime(file).toInstant();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else return Instant.EPOCH;
    }

    private static Instant maxTime(Instant... of) {
        Instant result = Instant.EPOCH;

        for (Instant each : of) {
            if (each.isAfter(result)) result = each;
        }

        return result;
    }

    /**
     * Find .jar files for external dependencies, for examples settings `externalDependencies` in
     * local maven / gradle repository.
     */
    Set<Path> buildClassPath() {
        // Settings `externalDependencies`
        Stream<Path> result =
                allExternalDependencies()
                        .stream()
                        .flatMap(artifact -> stream(findAnyJar(artifact, false)));

        // Settings `classPath`
        Stream<Path> classPathJars =
                classPath.stream().flatMap(jar -> stream(findWorkspaceJar(jar)));

        result = Stream.concat(result, classPathJars);

        // Bazel
        if (Files.exists(workspaceRoot.resolve("WORKSPACE"))) {
            Path bazelGenFiles = workspaceRoot.resolve("bazel-genfiles");

            if (Files.exists(bazelGenFiles) && Files.isSymbolicLink(bazelGenFiles)) {
                result = Stream.concat(result, bazelJars(bazelGenFiles));
            }
        }

        Path gradleBuild = workspaceRoot.resolve("build.gradle");

        if (Files.exists(gradleBuild)) {
          result = Stream.concat(result, getGradleDependencies().stream());
        }

        return result.collect(Collectors.toSet());
    }

    private Optional<Path> findWorkspaceJar(Path jar) {
        jar = workspaceRoot.resolve(jar);

        if (Files.exists(jar)) return Optional.of(jar);
        else {
            LOG.warning("No such file " + jar);

            return Optional.empty();
        }
    }

    /**
     * Find directories that contain java .class files in the workspace, for example files generated
     * by maven in target/classes
     */
    Set<Path> workspaceClassPath() {
        // Bazel
        if (Files.exists(workspaceRoot.resolve("WORKSPACE"))) {
            Path bazelBin = workspaceRoot.resolve("bazel-bin");

            if (Files.exists(bazelBin) && Files.isSymbolicLink(bazelBin)) {
                return bazelOutputDirectories(bazelBin).collect(Collectors.toSet());
            }
        }

        // Maven
        try {
            return Files.walk(workspaceRoot)
                    .flatMap(this::outputDirectory)
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Recognize build root files like pom.xml and return compiler output directories */
    private Stream<Path> outputDirectory(Path file) {
        if (file.getFileName().toString().equals("pom.xml")) {
            Path target = file.resolveSibling("target");

            if (Files.exists(target) && Files.isDirectory(target)) {
                return Stream.of(target.resolve("classes"), target.resolve("test-classes"));
            }
        }

        if (file.getFileName().toString().equals("build.gradle")) {
          Path target = file.resolveSibling("build");
            if (Files.exists(target) && Files.isDirectory(target)) {
                return Stream.of(target.resolve("classes"));
            }
        }

        return Stream.empty();
    }

    /**
     * Search bazel-bin for per-module output directories matching the pattern:
     *
     * <p>bazel-bin/path/to/module/_javac/rule/lib*_classes
     */
    private Stream<Path> bazelOutputDirectories(Path bazelBin) {
        try {
            Path target = Files.readSymbolicLink(bazelBin);
            PathMatcher match =
                    FileSystems.getDefault().getPathMatcher("glob:**/_javac/*/lib*_classes");

            return Files.walk(target).filter(match::matches).filter(Files::isDirectory);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Search bazel-genfiles for jars */
    private Stream<Path> bazelJars(Path bazelGenFiles) {
        try {
            Path target = Files.readSymbolicLink(bazelGenFiles);

            return Files.walk(target)
                    .filter(file -> file.getFileName().toString().endsWith(".jar"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Find source .jar files for `externalDependencies` in local maven / gradle repository. */
    Set<Path> buildDocPath() {
        return allExternalDependencies()
                .stream()
                .flatMap(artifact -> stream(findAnyJar(artifact, true)))
                .collect(Collectors.toSet());
    }

    private Optional<Path> findAnyJar(Artifact artifact, boolean source) {
        Optional<Path> maven = findMavenJar(artifact, source);

        if (maven.isPresent()) return maven;
        else return findGradleJar(artifact, source);
    }

    private Optional<Path> findMavenJar(Artifact artifact, boolean source) {
        Path jar =
                mavenHome
                        .resolve("repository")
                        .resolve(artifact.groupId.replace('.', File.separatorChar))
                        .resolve(artifact.artifactId)
                        .resolve(artifact.version)
                        .resolve(fileName(artifact, source));

        if (Files.exists(jar)) return Optional.of(jar);
        else return Optional.empty();
    }

    private Optional<Path> findGradleJar(Artifact artifact, boolean source) {
        // Search for caches/modules-*/files-*/groupId/artifactId/version/*/artifactId-version[-sources].jar
        Path base = gradleHome.resolve("caches");
        String pattern =
                "glob:"
                        + Joiner.on(File.separatorChar)
                                .join(
                                        base.toString(),
                                        "modules-*",
                                        "files-*",
                                        artifact.groupId,
                                        artifact.artifactId,
                                        artifact.version,
                                        "*",
                                        fileName(artifact, source));
        PathMatcher match = FileSystems.getDefault().getPathMatcher(pattern);

        try {
            return Files.walk(base, 7).filter(match::matches).findFirst();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String fileName(Artifact artifact, boolean source) {
        return artifact.artifactId + '-' + artifact.version + (source ? "-sources" : "") + ".jar";
    }

    private boolean isSourceJar(Path jar) {
        return jar.getFileName().toString().endsWith("-sources.jar");
    }

    private int compareMavenVersions(Path leftJar, Path rightJar) {
        return compareVersions(mavenVersion(leftJar), mavenVersion(rightJar));
    }

    private String[] mavenVersion(Path jar) {
        return jar.getParent().getFileName().toString().split("\\.");
    }

    private int compareVersions(String[] left, String[] right) {
        int n = Math.min(left.length, right.length);

        for (int i = 0; i < n; i++) {
            int each = left[i].compareTo(right[i]);

            if (each != 0) return each;
        }

        return 0;
    }

    private static <T> Stream<T> stream(Optional<T> option) {
        return option.map(Stream::of).orElseGet(Stream::empty);
    }

    static List<Artifact> dependencyList(Path pomXml) {
        Objects.requireNonNull(pomXml, "pom.xml path is null");

        try {
            // Tell maven to output deps to a temporary file
            Path outputFile = Files.createTempFile("deps", ".txt");

            String cmd =
                    String.format(
                            "%s dependency:list -DincludeScope=test -DoutputFile=%s",
                            getMvnCommand(), outputFile);
            File workingDirectory = pomXml.toAbsolutePath().getParent().toFile();
            int result = Runtime.getRuntime().exec(cmd, null, workingDirectory).waitFor();

            if (result != 0) throw new RuntimeException("`" + cmd + "` returned " + result);

            return readDependencyList(outputFile);
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    private File createTempGradleConfig() {
      try {
          File temp = File.createTempFile("tempGradle", ".config");
          File config = File.createTempFile("classpath", ".gradle");
          String separator = System.getProperty("line.separator");
          String classpathGradleConfig = String.join(
                separator,
                "boolean isResolvable(Configuration conf) {",
                "  // isCanBeResolved was added in Gradle 3.3. Previously, all configurations were resolvable",
                "  if (Configuration.class.declaredMethods.any { it.name == 'isCanBeResolved' }) {",
                "    return conf.canBeResolved",
                "  }",
                "  return true",
                "}",
                "File getBuildCacheForDependency(File dependency) {",
                "  String name = dependency.getName()",
                "  String home = System.getProperty('user.home')",
                "  String gradleCache = home + File.separator + '.gradle' + File.separator + 'caches' + File.separator",
                "  if (file(gradleCache).exists()) {",
                "    String include = 'transforms*' + File.separator + '**' + File.separator + name + File.separator + '**' + File.separator + 'classes.jar'",
                "    return fileTree(dir: gradleCache, include: include).files.find { it.isFile() }",
                "  } else {",
                "    return zipTree(dependency).files.find { it.isFile() && it.name.endsWith('jar') }",
                "  }",
                "}",
                "task classpath {",
                "  doLast {",
                "    HashSet<String> classpathFiles = new HashSet<String>()",
                "    for (proj in allprojects) {",
                "      for (conf in proj.configurations) {",
                "        if (isResolvable(conf)) {",
                "          for (dependency in conf) {",
                "            classpathFiles += dependency",
                "          }",
                "        }",
                "      }",
                "      def rjava = proj.getBuildDir().absolutePath + File.separator + 'intermediates' + File.separator + 'classes' + File.separator + 'debug'",
                "      def rFiles = new File(rjava)",
                "      if (rFiles.exists()) {",
                "        classpathFiles += rFiles",
                "      }",
                "      if (proj.hasProperty('android')) {",
                "        classpathFiles += proj.android.getBootClasspath()",
                "        if (proj.android.hasProperty('applicationVariants')) {",
                "          proj.android.applicationVariants.all { v ->",
                "            if (v.hasProperty('javaCompile')) {",
                "              classpathFiles += v.javaCompile.classpath",
                "            }",
                "            if (v.hasProperty('compileConfiguration')) {",
                "              v.compileConfiguration.each { dependency ->",
                "                classpathFiles += dependency",
                "              }",
                "            }",
                "            if (v.hasProperty('runtimeConfiguration')) {",
                "              v.runtimeConfiguration.each { dependency ->",
                "                classpathFiles += dependency",
                "              }",
                "            }",
                "            if (v.hasProperty('getApkLibraries')) {",
                "              println v.getApkLibraries()",
                "              classpathFiles += v.getApkLibraries()",
                "            }",
                "            if (v.hasProperty('getCompileLibraries')) {",
                "              classpathFiles += v.getCompileLibraries()",
                "            }",
                "          }",
                "        }",
                "        if (proj.android.hasProperty('libraryVariants')) {",
                "          proj.android.libraryVariants.all { v ->",
                "            classpathFiles += v.javaCompile.classpath.files",
                "          }",
                "        }",
                "      }",
                "    }",
                "    HashSet<String> computedPaths = new HashSet<String>()",
                "    for (dependency in classpathFiles) {",
                "      def path",
                "      if (dependency.name.endsWith('jar')) {",
                "        path = dependency",
                "      } else {",
                "        path = getBuildCacheForDependency(dependency)",
                "      }",
                "      println path",
                "    }",
                "  }",
                "}"
        );

          BufferedWriter bw = new BufferedWriter(new FileWriter(config));
          bw.write(classpathGradleConfig);
          bw.close();

          bw = new BufferedWriter(new FileWriter(temp));
          bw.write(String.format("rootProject{ apply from: '%s'} ", config.getAbsolutePath()));
          bw.close();

          return temp;

      } catch(IOException e){
        e.printStackTrace();
      }
      return null;
    }

    private List<Path> getGradleDependencies() {
        try {
            // Tell maven to output deps to a temporary file
            Path outputFile = Files.createTempFile("deps", ".txt");
            File config = createTempGradleConfig();
            if (config != null) {
              String cmd =
                      String.format(
                              "%s -I %s classpath",
                              getGradleCommand().toAbsolutePath().toString(), config.getAbsolutePath().toString());
              LOG.warning(cmd);
              Process classpathCommand = Runtime.getRuntime().exec(cmd, null, workspaceRoot.toFile());

              InputStream stdout = classpathCommand.getInputStream();

              InputStreamReader streamReader = new InputStreamReader(stdout);
              BufferedReader bufferedReader = new BufferedReader(streamReader);

              List<String> classpathLines = new ArrayList<String>();

              int result = classpathCommand.waitFor();
              LOG.warning(workspaceRoot.toFile().toString());

              LOG.warning(result + "");
              if (result != 0) throw new RuntimeException("`" + cmd + "` returned " + result);

              Pattern artifact = Pattern.compile("^.+?\\.jar$");
              List<Path> dependencies = bufferedReader.lines()
                    .map(String::trim)
                    .filter(line -> artifact.matcher(line).matches())
                    .map(Paths::get)
                    .collect(Collectors.toList());
              if (dependencies != null) {
                return dependencies;
              }
            }

            return new ArrayList<Path>();

        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static List<Path> readGradleDependencyList(Path outputFile) {
      Pattern artifact = Pattern.compile("^.+?\\.jar$");

      try (InputStream in = Files.newInputStream(outputFile)) {
            return new BufferedReader(new InputStreamReader(in))
                    .lines()
                    .map(String::trim)
                    .filter(line -> artifact.matcher(line).matches())
                    .map(Paths::get)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    private static List<Artifact> readDependencyList(Path outputFile) {
        Pattern artifact = Pattern.compile(".*:.*:.*:.*:.*");

        try (InputStream in = Files.newInputStream(outputFile)) {
            return new BufferedReader(new InputStreamReader(in))
                    .lines()
                    .map(String::trim)
                    .filter(line -> artifact.matcher(line).matches())
                    .map(Artifact::parse)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Get external dependencies from this.externalDependencies if available, or try to infer them.
     */
    private Collection<Artifact> allExternalDependencies() {
        if (!externalDependencies.isEmpty()) return externalDependencies;

        // If user does not specify java.externalDependencies, look for pom.xml
        Path pomXml = workspaceRoot.resolve("pom.xml");

        if (Files.exists(pomXml)) return dependencyList(pomXml);

        return Collections.emptyList();
    }

    private Path getGradleCommand() {
      Path gradleCommand = Paths.get(findExecutableOnPath("gradle"));
      Path unixGradle = workspaceRoot.resolve("gradlew");
      Path windowsGradle = workspaceRoot.resolve("gradlew.bat");

      if (File.separatorChar == '\\' && Files.exists(windowsGradle)) {
        gradleCommand = windowsGradle;
      } else if (Files.exists(unixGradle)) {
        gradleCommand = unixGradle;
      }

      return gradleCommand;
    }

    static String getMvnCommand() {
        String mvnCommand = "mvn";
        if (File.separatorChar == '\\') {
            mvnCommand = findExecutableOnPath("mvn.cmd");
            if (mvnCommand == null) {
                mvnCommand = findExecutableOnPath("mvn.bat");
            }
        }
        return mvnCommand;
    }

    private static String findExecutableOnPath(String name) {
        for (String dirname : System.getenv("PATH").split(File.pathSeparator)) {
            File file = new File(dirname, name);
            if (file.isFile() && file.canExecute()) {
                return file.getAbsolutePath();
            }
        }
        return null;
    }

    private static final Logger LOG = Logger.getLogger("main");
}
