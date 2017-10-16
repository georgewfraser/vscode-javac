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

import static org.javacs.Main.JSON;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

public class LegacyConfig {
    private final Path workspaceRoot;

    LegacyConfig(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    Optional<JavacConfig> readJavaConfig(Path dir) {
        Function<JavaConfigJson, JavacConfig> parseJavaConfigJson =
                json -> {
                    Set<Path> classPath = readClassPath(dir, json.classPath, json.classPathFile),
                            docPath = readClassPath(dir, json.docPath, json.docPathFile),
                            sourcePath =
                                    json.sourcePath
                                            .stream()
                                            .map(dir::resolve)
                                            .collect(Collectors.toSet());
                    Path outputDirectory = dir.resolve(json.outputDirectory);

                    return new JavacConfig(
                            classPath, Collections.singleton(outputDirectory), docPath);
                };
        if (Files.exists(dir.resolve("javaconfig.json"))) {
            return readJavaConfigJson(dir.resolve("javaconfig.json")).map(parseJavaConfigJson);
        } else return Optional.empty();
    }

    private Set<Path> readClassPath(
            Path dir, Set<Path> jsonClassPath, Optional<Path> jsonClassPathFile) {
        Set<Path> classPath = new HashSet<>();

        jsonClassPathFile.ifPresent(
                classPathFile -> {
                    Path classPathFilePath = dir.resolve(classPathFile);
                    Set<Path> paths = readClassPathFile(classPathFilePath);

                    classPath.addAll(paths);
                });

        jsonClassPath.forEach(entry -> classPath.add(dir.resolve(entry)));

        return classPath;
    }

    private JavacConfig readPomXml(Path dir, boolean testScope) {
        Path originalPom = dir.resolve("pom.xml");
        Path effectivePom = generateEffectivePom(originalPom);

        // Invoke maven to get classpath
        Set<Path> classPath = buildClassPath(originalPom, testScope, false);

        // Get source directory from pom.xml
        Set<Path> sourcePath = sourceDirectories(effectivePom, testScope);

        // Get doc path from pom.xml
        Set<Path> docPath = buildClassPath(originalPom, testScope, true);

        // Use maven output directory so incremental compilation uses maven-generated .class files
        Set<Path> workspaceClassPath =
                ImmutableSet.of(
                        Paths.get("target/test-classes").toAbsolutePath(),
                        Paths.get("target/classes").toAbsolutePath());

        JavacConfig config = new JavacConfig(classPath, workspaceClassPath, docPath);

        LOG.info("Inferred from " + originalPom + ":");
        LOG.info("\tsourcePath: " + Joiner.on(" ").join(sourcePath));
        LOG.info("\tclassPath: " + Joiner.on(" ").join(classPath));
        LOG.info("\tworkspaceClassPath: " + Joiner.on(" ").join(workspaceClassPath));
        LOG.info("\tdocPath: " + Joiner.on(" ").join(docPath));

        return config;
    }

    private static Path generateEffectivePom(Path pomXml) {
        try {
            Objects.requireNonNull(pomXml, "pom.xml path is null");

            Path effectivePom = Files.createTempFile("effective-pom", ".xml");

            LOG.info(String.format("Emit effective pom for %s to %s", pomXml, effectivePom));

            String cmd =
                    String.format(
                            "%s help:effective-pom -Doutput=%s",
                            InferConfig.getMvnCommand(), effectivePom);
            File workingDirectory = pomXml.toAbsolutePath().getParent().toFile();
            int result = Runtime.getRuntime().exec(cmd, null, workingDirectory).waitFor();

            if (result != 0) throw new RuntimeException("`" + cmd + "` returned " + result);

            return effectivePom;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    // TODO For sourceJars = true:
    // use mvn dependency:list to list the dependencies quickly,
    // then fetch each one individually using mvn dependency:build-classpath -DincludeGroupIds=? -DincludeArtifactIds=? ...
    private static Set<Path> buildClassPath(Path pomXml, boolean testScope, boolean sourceJars) {
        try {
            Objects.requireNonNull(pomXml, "pom.xml path is null");

            // Tell maven to output classpath to a temporary file
            // TODO if pom.xml already specifies outputFile, use that location
            Path classPathTxt =
                    Files.createTempFile(sourceJars ? "sourcepath" : "classpath", ".txt");

            LOG.info(
                    String.format(
                            "Emit %s to %s", sourceJars ? "docPath" : "classpath", classPathTxt));

            String cmd =
                    String.format(
                            "%s dependency:build-classpath -DincludeScope=%s -Dmdep.outputFile=%s %s",
                            InferConfig.getMvnCommand(),
                            testScope ? "test" : "compile",
                            classPathTxt,
                            sourceJars ? "-Dclassifier=sources" : "");
            File workingDirectory = pomXml.toAbsolutePath().getParent().toFile();
            int result = Runtime.getRuntime().exec(cmd, null, workingDirectory).waitFor();

            if (result != 0) throw new RuntimeException("`" + cmd + "` returned " + result);

            Set<Path> found = readClassPathFile(classPathTxt);

            LOG.info("Read " + Joiner.on(" ").join(found) + " from " + classPathTxt);

            return found;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static Set<Path> sourceDirectories(Path pomXml, boolean testScope) {
        return testScope
                ? ImmutableSet.of(
                        onlySourceDirectories(pomXml, true), onlySourceDirectories(pomXml, false))
                : ImmutableSet.of(onlySourceDirectories(pomXml, false));
    }

    private static Path onlySourceDirectories(Path pomXml, boolean testScope) {
        String defaultSourceDir = testScope ? "src/test/java" : "src/main/java";
        String xPath =
                testScope ? "/project/build/testSourceDirectory" : "/project/build/sourceDirectory";
        Document doc = parsePomXml(pomXml);

        try {
            String sourceDir = XPathFactory.newInstance().newXPath().compile(xPath).evaluate(doc);

            if (sourceDir == null || sourceDir.isEmpty()) {
                LOG.info("Use default source directory " + defaultSourceDir);

                sourceDir = defaultSourceDir;
            } else LOG.info("Use source directory from pom.xml " + sourceDir);

            return pomXml.resolveSibling(sourceDir).toAbsolutePath();
        } catch (XPathExpressionException e) {
            throw new RuntimeException(e);
        }
    }

    private static Document parsePomXml(Path pomXml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();

            return builder.parse(pomXml.toFile());
        } catch (IOException | ParserConfigurationException | SAXException e) {
            throw new RuntimeException(e);
        }
    }

    private Optional<JavaConfigJson> readJavaConfigJson(Path configFile) {
        try {
            JsonNode json = JSON.readValue(configFile.toFile(), JsonNode.class);

            if (json.isArray())
                return JSON.convertValue(json, new TypeReference<List<JavaConfigJson>>() {});
            else {
                JavaConfigJson one = JSON.convertValue(json, JavaConfigJson.class);

                return Optional.of(one);
            }
        } catch (IOException e) {
            MessageParams message = new MessageParams();

            message.setMessage("Error reading " + configFile);
            message.setType(MessageType.Error);

            throw new ShowMessageException(message, e);
        }
    }

    private static Set<Path> readClassPathFile(Path classPathFilePath) {
        try {
            InputStream in = Files.newInputStream(classPathFilePath);
            String text =
                    new BufferedReader(new InputStreamReader(in))
                            .lines()
                            .collect(Collectors.joining());
            Path dir = classPathFilePath.getParent();

            return Arrays.stream(text.split(File.pathSeparator))
                    .map(dir::resolve)
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            MessageParams message = new MessageParams();

            message.setMessage("Error reading " + classPathFilePath);
            message.setType(MessageType.Error);

            throw new ShowMessageException(message, e);
        }
    }

    private static final Logger LOG = Logger.getLogger("main");
}
