package org.javacs;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
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

    static JavacTask parseTask(Path source) {
        // TODO should get current contents of open files from FileStore
        JavaFileObject file =
                fileManager.getJavaFileObjectsFromFiles(Collections.singleton(source.toFile())).iterator().next();
        return parseTask(file);
    }

    static CompilationUnitTree parse(Path source) {
        try {
            return parseTask(source).parse().iterator().next();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void onError(javax.tools.Diagnostic<? extends JavaFileObject> err) {
        // Too noisy, this only comes up in parse tasks which tend to be less important
        // LOG.warning(err.getMessage(Locale.getDefault()));
    }

    static String describeTree(Tree leaf) {
        if (leaf instanceof MethodTree) {
            var method = (MethodTree) leaf;
            var params = new StringJoiner(", ");
            for (var p : method.getParameters()) {
                params.add(p.getType() + " " + p.getName());
            }
            return method.getName() + "(" + params + ")";
        }
        if (leaf instanceof ClassTree) {
            var cls = (ClassTree) leaf;
            return "class " + cls.getSimpleName();
        }
        if (leaf instanceof BlockTree) {
            var block = (BlockTree) leaf;
            return String.format("{ ...%d lines... }", block.getStatements().size());
        }
        return leaf.toString();
    }
}
