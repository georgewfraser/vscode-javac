package org.javacs_server;

import java.io.IOException;
import java.net.URI;
import javax.tools.SimpleJavaFileObject;

class StringFileObject extends SimpleJavaFileObject {
    private final String content;
    private final URI path; // TODO rename

    StringFileObject(String content, URI path) {
        super(path, Kind.SOURCE);

        this.content = content;
        this.path = path;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
        return content;
    }
}
