package org.javacs.config;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.nio.file.Paths;
import org.junit.Test;

public class InferBazelConfigTest {
    @Test
    public void bazelClassPath() {
        var bazel = Config.buildBazelConfig(Paths.get("src/test/examples/bazel-project"), "bazel");
        var classpath = bazel.classpath();
        System.out.println(classpath);
        assertThat(classpath, hasItem(hasToString(endsWith("guava-18.0.jar"))));
    }

    @Test
    public void bazelClassPathInSubdir() {
        var bazel = Config.buildBazelConfig(Paths.get("src/test/examples/bazel-project/hello"), "bazel");
        var classpath = bazel.classpath();
        System.out.println(classpath);
        assertThat(classpath, contains(hasToString(endsWith("guava-18.0.jar"))));
    }

    @Test
    public void bazelDocPath() {
        var bazel = Config.buildBazelConfig(Paths.get("src/test/examples/bazel-project"), "bazel");
        var sourcepath = bazel.sourcepath();
        System.out.println(sourcepath);
        assertThat(sourcepath, hasItem(hasToString(endsWith("guava-18.0-sources.jar"))));
    }

    @Test
    public void bazelDocPathInSubdir() {
        var bazel = Config.buildBazelConfig(Paths.get("src/test/examples/bazel-project/hello"), "bazel");
        var sourcepath = bazel.sourcepath();
        System.out.println(sourcepath);
        assertThat(sourcepath, contains(hasToString(endsWith("guava-18.0-sources.jar"))));
    }
}
