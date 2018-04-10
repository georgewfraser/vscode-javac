package org.javacs;

import java.util.Objects;

/**
 * Loads langtools off the classpath, which contains our custom build of javac, instead of the
 * platform. In Java 8, this was not necessary because tools.jar isn't loaded by default, so java
 * only sees our custom build of javac. But in Java 9, tools.jar is in the main platform, so we need
 * to avoid loading it through a custom classloader.
 */
class LangTools {
    public static final String[] LANGTOOLS_PACKAGES = {
        "com.sun.source",
        "com.sun.tools",
        "javax.annotation.processing",
        "javax.lang.model",
        "javax.tools",
        "org.javacs"
    };

    public static ClassLoader createLangToolsClassLoader() {
        String classPath = System.getProperty("java.class.path");
        Objects.requireNonNull(classPath, "java.class.path was null");
        ClassLoader parent = LangTools.class.getClassLoader();
        return ChildFirstClassLoader.fromClassPath(classPath, LANGTOOLS_PACKAGES, parent);
    }
}
