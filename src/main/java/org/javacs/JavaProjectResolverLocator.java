package org.javacs;

import java.nio.file.Files;
import java.util.Map;
import java.util.HashMap;
import java.nio.file.Path;
import java.util.Optional;

import org.javacs.resolver.ManualConfigProjectResolver;
import org.javacs.resolver.MavenProjectResolver;
import org.javacs.resolver.SBTProjectResolver;

class JavaProjectResolverLocator {
    private static Map<String, Class<? extends JavaProjectResolver>> projectResolvers = new HashMap<>();
    static {
        projectResolvers.put("javaconfig.json", ManualConfigProjectResolver.class);
        projectResolvers.put("pom.xml"  , MavenProjectResolver.class);
        projectResolvers.put("build.sbt", SBTProjectResolver.class);
    }

    static Optional<JavaProjectResolver> findResolver(Path dir){
        for(String k : projectResolvers.keySet()){
            Path projectFile = dir.resolve(k);
            if (Files.exists(projectFile)) {
                Class<? extends JavaProjectResolver> clazz = projectResolvers.get(k);
                JavaProjectResolver resolver;
                try {
                    resolver = clazz.newInstance();
                } catch (InstantiationException | IllegalAccessException _e) {
                    throw new RuntimeException(_e);
                }

                //initialize the projectResolver
                resolver.init(dir, projectFile);
                return Optional.of(resolver);
            }
        }
        return Optional.empty();
    }
}