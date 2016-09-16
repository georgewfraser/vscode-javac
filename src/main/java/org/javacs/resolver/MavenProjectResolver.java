package org.javacs.resolver;

import org.javacs.JavaProjectResolver;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

public class MavenProjectResolver implements JavaProjectResolver {
    private static final Logger LOG = Logger.getLogger("main");

    private Path baseDirectory;
    private Path projectFile;
    private String command;

    @Override
    public JavaProjectResolver init(Path baseDirectory, Path projectFile) {
        this.baseDirectory = baseDirectory;
        this.projectFile = projectFile;
        this.command = new CommandFinder("cmd","cmd.cmd","cmd.bat").findCommand();
        if(this.command == null || this.command.length() == 0){
            throw new RuntimeException("Maven could not be found in the classpath");
        }
        return this;
    }

    public Set<Path> resolveClassPath() {
        try {
            // Tell maven to output classpath to a temporary file
            // TODO if pom.xml already specifies outputFile, use that location
            Path classPathTxt = Files.createTempFile("classpath", ".txt");

            LOG.info("Emit classpath to " + classPathTxt);

            String cmd = command +" dependency:build-classpath -Dmdep.outputFile=" + classPathTxt;
            File workingDirectory = projectFile.toAbsolutePath().getParent().toFile();
            int result = Runtime.getRuntime().exec(cmd, null, workingDirectory).waitFor();
            if (result != 0)
                throw new RuntimeException("`" + cmd + "` returned " + result);

            ClassPathFileReader classPathFileReader = new ClassPathFileReader(classPathTxt);
            return classPathFileReader.load();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public Set<Path> resolveSourcePath() {
        try {
            Set<Path> all = new HashSet<>();

            // Parse pom.xml
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(projectFile.toFile());

            // Find source directory
            String sourceDir = XPathFactory.newInstance().newXPath().compile("/project/build/sourceDirectory").evaluate(doc);

            if (sourceDir == null || sourceDir.isEmpty()) {
                LOG.info("Use default source directory src/main/java");

                sourceDir = "src/main/java";
            } else LOG.info("Use source directory from pom.xml " + sourceDir);

            all.add(projectFile.resolveSibling(sourceDir).toAbsolutePath());

            // Find test directory
            String testDir = XPathFactory.newInstance().newXPath().compile("/project/build/testSourceDirectory").evaluate(doc);

            if (testDir == null || testDir.isEmpty()) {
                LOG.info("Use default test directory src/test/java");

                testDir = "src/test/java";
            } else LOG.info("Use test directory from pom.xml " + testDir);

            all.add(projectFile.resolveSibling(testDir).toAbsolutePath());

            return all;
        } catch (IOException | ParserConfigurationException | SAXException | XPathExpressionException e) {
            throw new RuntimeException(e);
        }

    }

    public Path resolveOutputPath() {
        return Paths.get("target/javacs").toAbsolutePath();
    }
}
