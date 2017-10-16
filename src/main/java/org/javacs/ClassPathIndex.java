/*
 * Original work Copyright (c) 2017 George W Fraser.
 * Modified work Copyright (c) 2017 Palantir Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 */

package org.javacs;

import com.google.common.reflect.ClassPath;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import sun.misc.Launcher;

/**
 * Index the classpath *without* using the java compiler API. The classpath can contain problematic
 * types, for example references to classes that *aren't* present. So we use reflection to find
 * class names and constructors on the classpath.
 *
 * <p>The isn't the only way we inspect the classpath---when completing members, for example, we use
 * the Javac API. This path is strictly for when we have to search the *entire* classpath.
 */
public class ClassPathIndex {

    private final List<ClassPath.ClassInfo> topLevelClasses;

    ClassPathIndex(Set<Path> classPath) {
        this.topLevelClasses =
                classPath(classLoader(classPath))
                        .getTopLevelClasses()
                        .stream()
                        .sorted(ClassPathIndex::shortestName)
                        .collect(Collectors.toList());
    }

    private static int shortestName(ClassPath.ClassInfo left, ClassPath.ClassInfo right) {
        return Integer.compare(left.getSimpleName().length(), right.getSimpleName().length());
    }

    public static URLClassLoader parentClassLoader() {
        URL[] bootstrap = Launcher.getBootstrapClassPath().getURLs();

        return new URLClassLoader(bootstrap, null);
    }

    private static ClassPath classPath(URLClassLoader classLoader) {
        try {
            return ClassPath.from(classLoader);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static URLClassLoader classLoader(Set<Path> classPath) {
        URL[] urls = classPath.stream().flatMap(ClassPathIndex::url).toArray(URL[]::new);

        return new URLClassLoader(urls, parentClassLoader());
    }

    private static Stream<URL> url(Path path) {
        try {
            return Stream.of(path.toUri().toURL());
        } catch (MalformedURLException e) {
            LOG.warning(e.getMessage());

            return Stream.empty();
        }
    }

    Stream<ClassPath.ClassInfo> topLevelClasses() {
        return topLevelClasses.stream();
    }

    boolean isAccessibleFromPackage(ClassPath.ClassInfo info, String fromPackage) {
        return info.getPackageName().equals(fromPackage) || isPublic(info);
    }

    private boolean isPublic(ClassPath.ClassInfo info) {
        return Modifier.isPublic(info.load().getModifiers());
    }

    boolean hasAccessibleConstructor(ClassPath.ClassInfo info, String fromPackage) {
        Class<?> load = info.load();
        boolean isPublicClass = Modifier.isPublic(load.getModifiers()),
                isSamePackage = fromPackage.equals(info.getPackageName());

        for (Constructor<?> candidate : load.getDeclaredConstructors()) {
            int modifiers = candidate.getModifiers();

            if (isPublicClass && Modifier.isPublic(modifiers)) return true;
            else if (isSamePackage
                    && !Modifier.isPrivate(modifiers)
                    && !Modifier.isProtected(modifiers)) return true;
        }

        return false;
    }

    /** Find all packages in parentPackage */
    Stream<String> packagesStartingWith(String partialPackage) {
        return topLevelClasses
                .stream()
                .filter(c -> c.getPackageName().startsWith(partialPackage))
                .map(c -> c.getPackageName());
    }

    Stream<ClassPath.ClassInfo> topLevelClassesIn(String parentPackage, String partialClass) {
        Predicate<ClassPath.ClassInfo> matches =
                c -> {
                    return c.getPackageName().equals(parentPackage)
                            && Completions.containsCharactersInOrder(
                                    c.getSimpleName(), partialClass, false);
                };

        return topLevelClasses.stream().filter(matches);
    }

    Optional<ClassPath.ClassInfo> loadPackage(String prefix) {
        return topLevelClasses
                .stream()
                .filter(c -> c.getPackageName().startsWith(prefix))
                .findAny();
    }

    private static final Logger LOG = Logger.getLogger("main");
}
