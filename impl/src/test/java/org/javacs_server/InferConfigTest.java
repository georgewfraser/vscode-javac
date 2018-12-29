package org.javacs_server;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Set;
import org.junit.Test;

public class InferConfigTest {
    private Path workspaceRoot = Paths.get("src/test/test-project/workspace");
    private Path mavenHome = Paths.get("src/test/test-project/home/.m2");
    private Path gradleHome = Paths.get("src/test/test-project/home/.gradle");
    private Set<String> externalDependencies = Set.of("com.external:external-library:1.2");
    private InferConfig both = new InferConfig(workspaceRoot, externalDependencies, mavenHome, gradleHome);
    private InferConfig gradle = new InferConfig(workspaceRoot, externalDependencies, Paths.get("nowhere"), gradleHome);
    private InferConfig onlyPomXml =
            new InferConfig(
                    Paths.get("src/test/test-project/only-pom-xml"),
                    Collections.emptySet(),
                    mavenHome,
                    Paths.get("nowhere"));

    @Test
    public void mavenClassPath() {
        assertThat(
                both.buildClassPath(),
                contains(mavenHome.resolve("repository/com/external/external-library/1.2/external-library-1.2.jar")));
        // v1.1 should be ignored
    }

    @Test
    public void gradleClasspath() {
        assertThat(
                gradle.buildClassPath(),
                contains(
                        gradleHome.resolve(
                                "caches/modules-2/files-2.1/com.external/external-library/1.2/xxx/external-library-1.2.jar")));
        // v1.1 should be ignored
    }

    @Test
    public void mavenDocPath() {
        assertThat(
                both.buildDocPath(),
                contains(
                        mavenHome.resolve(
                                "repository/com/external/external-library/1.2/external-library-1.2-sources.jar")));
        // v1.1 should be ignored
    }

    @Test
    public void gradleDocPath() {
        assertThat(
                gradle.buildDocPath(),
                contains(
                        gradleHome.resolve(
                                "caches/modules-2/files-2.1/com.external/external-library/1.2/yyy/external-library-1.2-sources.jar")));
        // v1.1 should be ignored
    }

    @Test
    public void dependencyList() {
        assertThat(
                InferConfig.dependencyList(Paths.get("pom.xml")),
                hasItem(new Artifact("org.hamcrest", "hamcrest-all", "1.3")));
    }

    @Test
    public void onlyPomXmlClassPath() {
        assertThat(
                onlyPomXml.buildClassPath(),
                contains(mavenHome.resolve("repository/com/external/external-library/1.2/external-library-1.2.jar")));
    }

    @Test
    public void onlyPomXmlDocPath() {
        assertThat(
                onlyPomXml.buildDocPath(),
                contains(
                        mavenHome.resolve(
                                "repository/com/external/external-library/1.2/external-library-1.2-sources.jar")));
    }
}
