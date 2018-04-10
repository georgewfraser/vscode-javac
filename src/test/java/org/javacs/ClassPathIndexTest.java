package org.javacs;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.common.reflect.ClassPath;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Optional;
import org.junit.Test;

public class ClassPathIndexTest {

    @Test
    public void createEmptyLoader() throws ClassNotFoundException {
        ClassLoader emptyClassLoader = ClassPathIndex.parentClassLoader();

        assertThat(emptyClassLoader.loadClass("java.util.ArrayList"), not(nullValue()));

        try {
            Class<?> found = emptyClassLoader.loadClass("com.google.common.collect.Lists");

            fail("Found " + found);
        } catch (ClassNotFoundException e) {
            // OK
        }
    }

    @Test
    public void java8Platform() throws IOException {
        String javaHome =
                Paths.get("./src/test/test-platforms/jdk8-home").toAbsolutePath().toString();
        URL[] resources = ClassPathIndex.java8Platform(javaHome);
        assertThat(
                "found example.jar",
                resources,
                hasItemInArray(hasToString(containsString("rt.jar"))));
        ClassPath classPath = ClassPath.from(new URLClassLoader(resources, null));
        assertThat(
                classPath.getTopLevelClasses(),
                hasItem(hasProperty("simpleName", equalTo("HelloWorld"))));
    }

    @Test
    public void java9Platform() throws IOException {
        String javaHome =
                Paths.get("./src/test/test-platforms/jdk9-home").toAbsolutePath().toString();
        URL[] resources = ClassPathIndex.java9Platform(javaHome);
        assertThat(
                "found java.compiler.jmod",
                resources,
                hasItemInArray(hasToString(containsString("java.compiler.jmod"))));
        ClassPath classPath = ClassPath.from(new URLClassLoader(resources, null));
        assertThat(
                classPath.getTopLevelClasses(),
                hasItem(hasProperty("simpleName", equalTo("JavaCompiler"))));
    }

    @Test
    public void topLevelClasses() {
        ClassPathIndex index = new ClassPathIndex(Collections.emptySet());
        Optional<ClassPath.ClassInfo> arrayList =
                index.topLevelClasses()
                        .filter(c -> c.getName().equals("java.util.ArrayList"))
                        .findFirst();
        assertTrue("java.util.ArrayList is on the classpath", arrayList.isPresent());
    }
}
