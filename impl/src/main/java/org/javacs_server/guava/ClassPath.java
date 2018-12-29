/*
 * Copyright (C) 2012 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.javacs_server.guava;

import static java.util.logging.Level.WARNING;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Scans the source of a {@link ClassLoader} and finds all loadable classes and resources.
 *
 * <p><b>Warning:</b> Current limitations:
 *
 * <ul>
 *   <li>Looks only for files and JARs in URLs available from {@link URLClassLoader} instances or the {@linkplain
 *       ClassLoader#getSystemClassLoader() system class loader}.
 *   <li>Only understands {@code file:} URLs.
 * </ul>
 *
 * <p>In the case of directory classloaders, symlinks are supported but cycles are not traversed. This guarantees
 * discovery of each <em>unique</em> loadable resource. However, not all possible aliases for resources on cyclic paths
 * will be listed.
 *
 * @author Ben Yu
 * @since 14.0
 */
public final class ClassPath {
    private static final Logger logger = Logger.getLogger(ClassPath.class.getName());

    private static final String CLASS_FILE_NAME_EXTENSION = ".class";

    private final Set<ResourceInfo> resources;

    private ClassPath(Set<ResourceInfo> resources) {
        this.resources = resources;
    }

    /**
     * Returns a {@code ClassPath} representing all classes and resources loadable from {@code classloader} and its
     * ancestor class loaders.
     *
     * <p><b>Warning:</b> {@code ClassPath} can find classes and resources only from:
     *
     * <ul>
     *   <li>{@link URLClassLoader} instances' {@code file:} URLs
     *   <li>the {@linkplain ClassLoader#getSystemClassLoader() system class loader}. To search the system class loader
     *       even when it is not a {@link URLClassLoader} (as in Java 9), {@code ClassPath} searches the files from the
     *       {@code java.class.path} system property.
     * </ul>
     *
     * @throws IOException if the attempt to read class path resources (jar files or directories) failed.
     */
    public static ClassPath from(ClassLoader classloader) throws IOException {
        DefaultScanner scanner = new DefaultScanner();
        scanner.scan(classloader);
        return new ClassPath(scanner.getResources());
    }

    /**
     * Returns all resources loadable from the current class path, including the class files of all loadable classes but
     * excluding the "META-INF/MANIFEST.MF" file.
     */
    public Set<ResourceInfo> getResources() {
        return resources;
    }

    private Stream<ClassInfo> filterClassInfo(Object any) {
        if (any instanceof ClassInfo) return Stream.of((ClassInfo) any);
        else return Stream.empty();
    }

    /**
     * Returns all classes loadable from the current class path.
     *
     * @since 16.0
     */
    public Set<ClassInfo> getAllClasses() {
        return resources.stream().flatMap(this::filterClassInfo).collect(Collectors.toSet());
    }

    private boolean isTopLevel(ClassInfo info) {
        return info.className.indexOf('$') == -1;
    }

    /** Returns all top level classes loadable from the current class path. */
    public Set<ClassInfo> getTopLevelClasses() {
        return resources.stream().flatMap(this::filterClassInfo).filter(this::isTopLevel).collect(Collectors.toSet());
    }

    /** Returns all top level classes whose package name is {@code packageName}. */
    public Set<ClassInfo> getTopLevelClasses(String packageName) {
        assert packageName != null;
        var builder = new HashSet<ClassInfo>();
        for (ClassInfo classInfo : getTopLevelClasses()) {
            if (classInfo.getPackageName().equals(packageName)) {
                builder.add(classInfo);
            }
        }
        return builder;
    }

    /**
     * Returns all top level classes whose package name is {@code packageName} or starts with {@code packageName}
     * followed by a '.'.
     */
    public Set<ClassInfo> getTopLevelClassesRecursive(String packageName) {
        assert packageName != null;
        String packagePrefix = packageName + '.';
        var builder = new HashSet<ClassInfo>();
        for (ClassInfo classInfo : getTopLevelClasses()) {
            if (classInfo.getName().startsWith(packagePrefix)) {
                builder.add(classInfo);
            }
        }
        return builder;
    }

    /**
     * Represents a class path resource that can be either a class file or any other resource file loadable from the
     * class path.
     *
     * @since 14.0
     */
    public static class ResourceInfo {
        private final String resourceName;

        final ClassLoader loader;

        static ResourceInfo of(String resourceName, ClassLoader loader) {
            if (resourceName.endsWith(CLASS_FILE_NAME_EXTENSION)) {
                return new ClassInfo(resourceName, loader);
            } else {
                return new ResourceInfo(resourceName, loader);
            }
        }

        ResourceInfo(String resourceName, ClassLoader loader) {
            assert resourceName != null;
            assert loader != null;
            this.resourceName = resourceName;
            this.loader = loader;
        }

        /**
         * Returns the url identifying the resource.
         *
         * <p>See {@link ClassLoader#getResource}
         *
         * @throws NoSuchElementException if the resource cannot be loaded through the class loader, despite physically
         *     existing in the class path.
         */
        public final URL url() {
            URL url = loader.getResource(resourceName);
            if (url == null) {
                throw new NoSuchElementException(resourceName);
            }
            return url;
        }

        /** Returns the fully qualified name of the resource. Such as "com/mycomp/foo/bar.txt". */
        public final String getResourceName() {
            return resourceName;
        }

        @Override
        public int hashCode() {
            return resourceName.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof ResourceInfo) {
                ResourceInfo that = (ResourceInfo) obj;
                return resourceName.equals(that.resourceName) && loader == that.loader;
            }
            return false;
        }

        // Do not change this arbitrarily. We rely on it for sorting ResourceInfo.
        @Override
        public String toString() {
            return resourceName;
        }
    }

    /**
     * Represents a class that can be loaded through {@link #load}.
     *
     * @since 14.0
     */
    public static final class ClassInfo extends ResourceInfo {
        private final String className;

        ClassInfo(String resourceName, ClassLoader loader) {
            super(resourceName, loader);
            this.className = getClassName(resourceName);
        }

        /**
         * Returns the package name of the class, without attempting to load the class.
         *
         * <p>Behaves identically to {@link Package#getName()} but does not require the class (or package) to be loaded.
         */
        public String getPackageName() {
            int lastDot = className.lastIndexOf('.');
            return (lastDot < 0) ? "" : className.substring(0, lastDot);
        }

        /**
         * Returns the simple name of the underlying class as given in the source code.
         *
         * <p>Behaves identically to {@link Class#getSimpleName()} but does not require the class to be loaded.
         */
        public String getSimpleName() {
            int lastDollarSign = className.lastIndexOf('$');
            if (lastDollarSign != -1) {
                String innerClassName = className.substring(lastDollarSign + 1);
                // local and anonymous classes are prefixed with number (1,2,3...), anonymous classes are
                // entirely numeric whereas local classes have the user supplied name as a suffix
                var prefix = 0;
                while (prefix < innerClassName.length() && Character.isDigit(innerClassName.charAt(prefix))) {
                    prefix++;
                }
                return innerClassName.substring(prefix);
            }
            String packageName = getPackageName();
            if (packageName.isEmpty()) {
                return className;
            }

            // Since this is a top level class, its simple name is always the part after package name.
            return className.substring(packageName.length() + 1);
        }

        /**
         * Returns the fully qualified name of the class.
         *
         * <p>Behaves identically to {@link Class#getName()} but does not require the class to be loaded.
         */
        public String getName() {
            return className;
        }

        /**
         * Loads (but doesn't link or initialize) the class.
         *
         * @throws LinkageError when there were errors in loading classes that this class depends on. For example,
         *     {@link NoClassDefFoundError}.
         */
        public Class<?> load() {
            try {
                return loader.loadClass(className);
            } catch (ClassNotFoundException e) {
                // Shouldn't happen, since the class name is read from the class path.
                throw new IllegalStateException(e);
            }
        }

        @Override
        public String toString() {
            return className;
        }
    }

    /**
     * Abstract class that scans through the class path represented by a {@link ClassLoader} and calls {@link
     * #scanDirectory} and {@link #scanJarFile} for directories and jar files on the class path respectively.
     */
    abstract static class Scanner {

        // We only scan each file once independent of the classloader that resource might be associated
        // with.
        private final Set<File> scannedUris = new HashSet<>();

        public final void scan(ClassLoader classloader) throws IOException {
            for (Entry<File, ClassLoader> entry : getClassPathEntries(classloader).entrySet()) {
                scan(entry.getKey(), entry.getValue());
            }
        }

        final void scan(File file, ClassLoader classloader) throws IOException {
            if (scannedUris.add(file.getCanonicalFile())) {
                scanFrom(file, classloader);
            }
        }

        /** Called when a directory is scanned for resource files. */
        protected abstract void scanDirectory(ClassLoader loader, File directory) throws IOException;

        /** Called when a jar file is scanned for resource entries. */
        protected abstract void scanJarFile(ClassLoader loader, JarFile file) throws IOException;

        private void scanFrom(File file, ClassLoader classloader) throws IOException {
            try {
                if (!file.exists()) {
                    return;
                }
            } catch (SecurityException e) {
                logger.warning("Cannot access " + file + ": " + e);
                // TODO(emcmanus): consider whether to log other failure cases too.
                return;
            }
            if (file.isDirectory()) {
                scanDirectory(classloader, file);
            } else {
                scanJar(file, classloader);
            }
        }

        private void scanJar(File file, ClassLoader classloader) throws IOException {
            JarFile jarFile;
            try {
                jarFile = new JarFile(file);
            } catch (IOException e) {
                // Not a jar file
                return;
            }
            try {
                for (File path : getClassPathFromManifest(file, jarFile.getManifest())) {
                    scan(path, classloader);
                }
                scanJarFile(classloader, jarFile);
            } finally {
                try {
                    jarFile.close();
                } catch (IOException ignored) {
                }
            }
        }

        /**
         * Returns the class path URIs specified by the {@code Class-Path} manifest attribute, according to <a
         * href="http://docs.oracle.com/javase/8/docs/technotes/guides/jar/jar.html#Main_Attributes">JAR File
         * Specification</a>. If {@code manifest} is null, it means the jar file has no manifest, and an empty set will
         * be returned.
         */
        static Set<File> getClassPathFromManifest(File jarFile, Manifest manifest) {
            if (manifest == null) {
                return Set.of();
            }
            var builder = new HashSet<File>();
            String classpathAttribute = manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH.toString());
            if (classpathAttribute != null) {
                for (String path : classpathAttribute.split(" ")) {
                    if (path.isEmpty()) continue;
                    URL url;
                    try {
                        url = getClassPathEntry(jarFile, path);
                    } catch (MalformedURLException e) {
                        // Ignore bad entry
                        logger.warning("Invalid Class-Path entry: " + path);
                        continue;
                    }
                    if (url.getProtocol().equals("file")) {
                        builder.add(toFile(url));
                    }
                }
            }
            return Collections.unmodifiableSet(builder);
        }

        static Map<File, ClassLoader> getClassPathEntries(ClassLoader classloader) {
            LinkedHashMap<File, ClassLoader> entries = new LinkedHashMap<>();
            // Search parent first, since it's the order ClassLoader#loadClass() uses.
            ClassLoader parent = classloader.getParent();
            if (parent != null) {
                entries.putAll(getClassPathEntries(parent));
            }
            for (URL url : getClassLoaderUrls(classloader)) {
                if (url.getProtocol().equals("file")) {
                    File file = toFile(url);
                    if (!entries.containsKey(file)) {
                        entries.put(file, classloader);
                    }
                }
            }
            return Collections.unmodifiableMap(entries);
        }

        private static List<URL> getClassLoaderUrls(ClassLoader classloader) {
            if (classloader instanceof URLClassLoader) {
                return List.of(((URLClassLoader) classloader).getURLs());
            }
            if (classloader.equals(ClassLoader.getSystemClassLoader())) {
                return parseJavaClassPath();
            }
            return List.of();
        }

        /**
         * Returns the URLs in the class path specified by the {@code java.class.path} {@linkplain System#getProperty
         * system property}.
         */
        // TODO(b/65488446): Make this a public API.
        static List<URL> parseJavaClassPath() {
            var urls = new ArrayList<URL>();
            var classPath = System.getProperty("java.class.path");
            var sep = System.getProperty("path.separator");
            for (String entry : classPath.split(sep)) {
                try {
                    try {
                        urls.add(new File(entry).toURI().toURL());
                    } catch (SecurityException e) { // File.toURI checks to see if the file is a directory
                        urls.add(new URL("file", null, new File(entry).getAbsolutePath()));
                    }
                } catch (MalformedURLException e) {
                    logger.log(WARNING, "malformed classpath entry: " + entry, e);
                }
            }
            return Collections.unmodifiableList(urls);
        }

        /**
         * Returns the absolute uri of the Class-Path entry value as specified in <a
         * href="http://docs.oracle.com/javase/8/docs/technotes/guides/jar/jar.html#Main_Attributes">JAR File
         * Specification</a>. Even though the specification only talks about relative urls, absolute urls are actually
         * supported too (for example, in Maven surefire plugin).
         */
        static URL getClassPathEntry(File jarFile, String path) throws MalformedURLException {
            return new URL(jarFile.toURI().toURL(), path);
        }
    }

    static final class DefaultScanner extends Scanner {
        private final Map<ClassLoader, Set<String>> resources = new HashMap<>();

        Set<ResourceInfo> getResources() {
            var builder = new HashSet<ResourceInfo>();
            for (var entry : resources.entrySet()) {
                for (var value : entry.getValue()) {
                    builder.add(ResourceInfo.of(value, entry.getKey()));
                }
            }
            return Collections.unmodifiableSet(builder);
        }

        @Override
        protected void scanJarFile(ClassLoader classloader, JarFile file) {
            Enumeration<JarEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || entry.getName().equals(JarFile.MANIFEST_NAME)) {
                    continue;
                }
                resources.computeIfAbsent(classloader, __ -> new LinkedHashSet<>()).add(entry.getName());
            }
        }

        @Override
        protected void scanDirectory(ClassLoader classloader, File directory) throws IOException {
            Set<File> currentPath = new HashSet<>();
            currentPath.add(directory.getCanonicalFile());
            scanDirectory(directory, classloader, "", currentPath);
        }

        /**
         * Recursively scan the given directory, adding resources for each file encountered. Symlinks which have already
         * been traversed in the current tree path will be skipped to eliminate cycles; otherwise symlinks are
         * traversed.
         *
         * @param directory the root of the directory to scan
         * @param classloader the classloader that includes resources found in {@code directory}
         * @param packagePrefix resource path prefix inside {@code classloader} for any files found under {@code
         *     directory}
         * @param currentPath canonical files already visited in the current directory tree path, for cycle elimination
         */
        private void scanDirectory(File directory, ClassLoader classloader, String packagePrefix, Set<File> currentPath)
                throws IOException {
            File[] files = directory.listFiles();
            if (files == null) {
                logger.warning("Cannot read directory " + directory);
                // IO error, just skip the directory
                return;
            }
            for (File f : files) {
                String name = f.getName();
                if (f.isDirectory()) {
                    File deref = f.getCanonicalFile();
                    if (currentPath.add(deref)) {
                        scanDirectory(deref, classloader, packagePrefix + name + "/", currentPath);
                        currentPath.remove(deref);
                    }
                } else {
                    String resourceName = packagePrefix + name;
                    if (!resourceName.equals(JarFile.MANIFEST_NAME)) {
                        resources.computeIfAbsent(classloader, __ -> new LinkedHashSet<>()).add(resourceName);
                    }
                }
            }
        }
    }

    static String getClassName(String filename) {
        int classNameEnd = filename.length() - CLASS_FILE_NAME_EXTENSION.length();
        return filename.substring(0, classNameEnd).replace('/', '.');
    }

    // TODO(benyu): Try java.nio.file.Paths#get() when Guava drops JDK 6 support.

    static File toFile(URL url) {
        assert url.getProtocol().equals("file");
        try {
            return new File(url.toURI()); // Accepts escaped characters like %20.
        } catch (URISyntaxException e) { // URL.toURI() doesn't escape chars.
            return new File(url.getPath()); // Accepts non-escaped chars like space.
        }
    }
}
