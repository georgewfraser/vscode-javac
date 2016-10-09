package org.javacs.resolver;

import io.typefox.lsapi.MessageType;
import io.typefox.lsapi.impl.MessageParamsImpl;
import org.javacs.JavaConfigJson;
import org.javacs.JavaProjectResolver;
import org.javacs.ShowMessageException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

import static org.javacs.Main.JSON;

public class ManualConfigProjectResolver implements JavaProjectResolver {
    private Path baseDirectory;
    private JavaConfigJson javaConfigJson;

    private JavaConfigJson readJavaConfigJson(Path configFile) {
        try {
            return JSON.readValue(configFile.toFile(), JavaConfigJson.class);
        } catch (IOException e) {
            MessageParamsImpl message = new MessageParamsImpl();

            message.setMessage("Error reading " + configFile);
            message.setType(MessageType.Error);
            throw new ShowMessageException(message, e);
        }
    }

    @Override
    public JavaProjectResolver init(Path baseDirectory, Path projectFile) {
        this.baseDirectory = baseDirectory;
        javaConfigJson = readJavaConfigJson(projectFile);
        return this;
    }

    @Override
    public Set<Path> resolveClassPath() {
        Set<Path> classPath = javaConfigJson.classPathFile.map(classPathFile -> {
            Path classPathFilePath = baseDirectory.resolve(classPathFile);
            ClassPathFileReader classPathFileReader = new ClassPathFileReader(classPathFilePath);
            return classPathFileReader.load();
        }).orElse(Collections.emptySet());

        return classPath;
    }

    @Override
    public Set<Path> resolveSourcePath() {
        return javaConfigJson.sourcePath
                .stream()
                .map(baseDirectory::resolve)
                .collect(Collectors.toSet());
    }

    @Override
    public Path resolveOutputPath() {
        return baseDirectory.resolve(javaConfigJson.outputDirectory);
    }
}
