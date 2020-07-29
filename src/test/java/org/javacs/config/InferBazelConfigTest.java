package org.javacs.config;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.nio.file.Paths;
import org.junit.Test;

public class InferBazelConfigTest {
    @Test
    public void bazelClassPath() {
        var bazel = BazelConfig.buildConfig(Paths.get("src/test/examples/bazel-project"));
        assertThat(bazel.classpath(), contains(hasToString(endsWith("guava-18.0.jar"))));
    }

    @Test
    public void bazelClassPathInSubdir() {
        var bazel = BazelConfig.buildConfig(Paths.get("src/test/examples/bazel-project/hello"));
        assertThat(bazel.classpath(), contains(hasToString(endsWith("guava-18.0.jar"))));
    }

    @Test
    public void bazelDocPath() {
        var bazel = BazelConfig.buildConfig(Paths.get("src/test/examples/bazel-project"));
        assertThat(bazel.sourcepath(), contains(hasToString(endsWith("guava-18.0-sources.jar"))));
    }

    @Test
    public void bazelDocPathInSubdir() {
        var bazel = BazelConfig.buildConfig(Paths.get("src/test/examples/bazel-project/hello"));
        assertThat(bazel.sourcepath(), contains(hasToString(endsWith("guava-18.0-sources.jar"))));
    }
}
