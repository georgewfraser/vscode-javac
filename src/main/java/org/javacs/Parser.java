package org.javacs;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.*;
import javax.tools.*;
import org.javacs.lsp.*;

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

    static List<TreePath> findSymbolsMatching(CompilationUnitTree parse, String query) {
        class Find extends TreePathScanner<Void, Void> {
            List<TreePath> found = new ArrayList<>();

            void accept(TreePath path) {
                var node = path.getLeaf();
                if (node instanceof ClassTree) {
                    var c = (ClassTree) node;
                    if (StringSearch.matchesTitleCase(c.getSimpleName(), query)) found.add(path);
                } else if (node instanceof MethodTree) {
                    var m = (MethodTree) node;
                    if (StringSearch.matchesTitleCase(m.getName(), query)) found.add(path);
                } else if (node instanceof VariableTree) {
                    var v = (VariableTree) node;
                    if (StringSearch.matchesTitleCase(v.getName(), query)) found.add(path);
                }
            }

            @Override
            public Void visitClass(ClassTree node, Void nothing) {
                super.visitClass(node, nothing);
                accept(getCurrentPath());
                for (var t : node.getMembers()) {
                    var child = new TreePath(getCurrentPath(), t);
                    accept(child);
                }
                return null;
            }

            List<TreePath> run() {
                scan(parse, null);
                return found;
            }
        }
        return new Find().run();
    }

    static Location location(TreePath p) {
        // This is very questionable, will this Trees object actually work?
        var task = parseTask(p.getCompilationUnit().getSourceFile());
        var trees = Trees.instance(task);
        var pos = trees.getSourcePositions();
        var cu = p.getCompilationUnit();
        var lines = cu.getLineMap();
        long start = pos.getStartPosition(cu, p.getLeaf()), end = pos.getEndPosition(cu, p.getLeaf());
        int startLine = (int) lines.getLineNumber(start) - 1, startCol = (int) lines.getColumnNumber(start) - 1;
        int endLine = (int) lines.getLineNumber(end) - 1, endCol = (int) lines.getColumnNumber(end) - 1;
        var dUri = cu.getSourceFile().toUri();
        return new Location(dUri, new Range(new Position(startLine, startCol), new Position(endLine, endCol)));
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
