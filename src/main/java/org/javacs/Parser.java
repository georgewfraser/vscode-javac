package org.javacs;

import com.sun.source.util.*;
import java.nio.charset.Charset;
import java.util.*;
import javax.tools.*;

class Parser {

    // TODO merge Parser with ParseFile

    private static final JavaCompiler compiler = ServiceLoader.load(JavaCompiler.class).iterator().next();
    private static final StandardJavaFileManager fileManager =
            compiler.getStandardFileManager(__ -> {}, null, Charset.defaultCharset());

    static JavacTask parseTask(JavaFileObject file) {
        // TODO the fixed cost of creating a task is greater than the cost of parsing 1 file; eliminate the task
        // creation
        return (JavacTask)
                compiler.getTask(
                        null,
                        fileManager,
                        Parser::onError,
                        Collections.emptyList(),
                        null,
                        Collections.singletonList(file));
    }

    private static void onError(javax.tools.Diagnostic<? extends JavaFileObject> err) {
        // Too noisy, this only comes up in parse tasks which tend to be less important
        // LOG.warning(err.getMessage(Locale.getDefault()));
    }
}
