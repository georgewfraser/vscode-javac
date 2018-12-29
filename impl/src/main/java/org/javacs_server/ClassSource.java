package org.javacs_server;

import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

interface ClassSource {
    Set<String> classes();

    Optional<Class<?>> load(String className);

    static final Logger LOG = Logger.getLogger("main");
    static final Set<String> failedToLoad = new HashSet<>();

    default boolean isPublic(String className) {
        if (failedToLoad.contains(className)) return false;
        try {
            return load(className).map(c -> Modifier.isPublic(c.getModifiers())).orElse(false);
        } catch (Exception e) {
            LOG.warning(String.format("Failed to load %s: %s", className, e.getMessage()));
            failedToLoad.add(className);
            return false;
        }
    }

    default boolean isAccessibleFromPackage(String className, String fromPackage) {
        var packageName = Parser.mostName(className);
        return packageName.equals(fromPackage) || isPublic(className);
    }
}
