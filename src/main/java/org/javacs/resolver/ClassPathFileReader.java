package org.javacs.resolver;

import io.typefox.lsapi.MessageType;
import io.typefox.lsapi.impl.MessageParamsImpl;
import org.javacs.ShowMessageException;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

class ClassPathFileReader {
    private Path classPathFilePath;

    ClassPathFileReader(Path classPathFilePath) {

        this.classPathFilePath = classPathFilePath;
    }

    Set<Path> load() {
        try {
            InputStream in = Files.newInputStream(classPathFilePath);
            String text = new BufferedReader(new InputStreamReader(in))
                    .lines()
                    .collect(Collectors.joining());
            Path dir = classPathFilePath.getParent();

            return Arrays.stream(text.split(File.pathSeparator))
                    .map(dir::resolve)
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            MessageParamsImpl message = new MessageParamsImpl();

            message.setMessage("Error reading " + classPathFilePath);
            message.setType(MessageType.Error);

            throw new ShowMessageException(message, e);
        }
    }
}
