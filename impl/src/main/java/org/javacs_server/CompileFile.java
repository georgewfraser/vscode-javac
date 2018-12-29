package org.javacs_server;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.IOException;
import java.net.URI;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;
import javax.lang.model.element.*;
import org.javacs.lsp.Range;

public class CompileFile {
    private final JavaCompilerService parent;
    final URI file;
    final String contents;
    private final JavacTask task;
    private final Trees trees;
    private final CompilationUnitTree root;

    CompileFile(JavaCompilerService parent, URI file, String contents) {
        this.parent = parent;
        this.file = file;
        this.contents = contents;
        this.task = CompileFocus.singleFileTask(parent, file, contents);
        this.trees = Trees.instance(task);
        var profiler = new Profiler();
        task.addTaskListener(profiler);
        try {
            var it = task.parse().iterator();
            this.root = it.hasNext() ? it.next() : null; // TODO something better than null when no class is present
            // The results of task.analyze() are unreliable when errors are present
            // You can get at `Element` values using `Trees`
            task.analyze();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        profiler.print();
    }

    public Optional<Element> element(int line, int character) {
        var path = CompileFocus.findPath(task, root, line, character);
        var el = trees.getElement(path);
        return Optional.ofNullable(el);
    }

    public Optional<TreePath> find(Ptr target) {
        class FindPtr extends TreePathScanner<Void, Void> {
            TreePath found = null;

            boolean toStringEquals(Object left, Object right) {
                return Objects.equals(Objects.toString(left, ""), Objects.toString(right, ""));
            }

            /** Check if the declaration at the current path is the same symbol as `e` */
            boolean sameSymbol() {
                return new Ptr(getCurrentPath()).equals(target);
            }

            void check() {
                if (sameSymbol()) {
                    found = getCurrentPath();
                }
            }

            @Override
            public Void visitClass(ClassTree node, Void aVoid) {
                check();
                return super.visitClass(node, aVoid);
            }

            @Override
            public Void visitMethod(MethodTree node, Void aVoid) {
                check();
                return super.visitMethod(node, aVoid);
            }

            @Override
            public Void visitVariable(VariableTree node, Void aVoid) {
                check();
                return super.visitVariable(node, aVoid);
            }
        }
        var find = new FindPtr();
        find.scan(root, null);
        return Optional.ofNullable(find.found);
    }

    public Optional<Range> range(TreePath path) {
        return ParseFile.range(task, contents, path);
    }

    /**
     * Figure out what imports this file should have. Star-imports like `import java.util.*` are converted to individual
     * class imports. Missing imports are inferred by looking at imports in other source files.
     */
    public FixImports fixImports() {
        // Check diagnostics for missing imports
        var unresolved = new HashSet<String>();
        for (var d : parent.diags) {
            if (d.getCode().equals("compiler.err.cant.resolve.location") && d.getSource().toUri().equals(file)) {
                long start = d.getStartPosition(), end = d.getEndPosition();
                var id = contents.substring((int) start, (int) end);
                if (id.matches("[A-Z]\\w+")) {
                    unresolved.add(id);
                } else LOG.warning(id + " doesn't look like a class");
            } else if (d.getMessage(null).contains("cannot find to")) {
                var lines = d.getMessage(null).split("\n");
                var firstLine = lines.length > 0 ? lines[0] : "";
                LOG.warning(String.format("%s %s doesn't look like to-not-found", d.getCode(), firstLine));
            }
        }
        // Look at imports in other classes to help us guess how to fix imports
        var sourcePathImports = Parser.existingImports(parent.sourcePath);
        var classes = new HashSet<String>();
        classes.addAll(parent.jdkClasses.classes());
        classes.addAll(parent.classPathClasses.classes());
        var fixes = Parser.resolveSymbols(unresolved, sourcePathImports, classes);
        // Figure out which existing imports are actually used
        var trees = Trees.instance(task);
        var references = new HashSet<String>();
        class FindUsedImports extends TreePathScanner<Void, Void> {
            @Override
            public Void visitIdentifier(IdentifierTree node, Void nothing) {
                var e = trees.getElement(getCurrentPath());
                if (e instanceof TypeElement) {
                    var t = (TypeElement) e;
                    var qualifiedName = t.getQualifiedName().toString();
                    var lastDot = qualifiedName.lastIndexOf('.');
                    var packageName = lastDot == -1 ? "" : qualifiedName.substring(0, lastDot);
                    var thisPackage = Objects.toString(root.getPackageName(), "");
                    // java.lang.* and current package are imported by default
                    if (!packageName.equals("java.lang")
                            && !packageName.equals(thisPackage)
                            && !packageName.equals("")) {
                        references.add(qualifiedName);
                    }
                }
                return null;
            }
        }
        new FindUsedImports().scan(root, null);
        // Take the intersection of existing imports ^ existing identifiers
        var qualifiedNames = new HashSet<String>();
        for (var i : root.getImports()) {
            var imported = i.getQualifiedIdentifier().toString();
            if (imported.endsWith(".*")) {
                var packageName = Parser.mostName(imported);
                var isUsed = references.stream().anyMatch(r -> r.startsWith(packageName));
                if (isUsed) qualifiedNames.add(imported);
                else LOG.warning("There are no references to package " + imported);
            } else {
                if (references.contains(imported)) qualifiedNames.add(imported);
                else LOG.warning("There are no references to class " + imported);
            }
        }
        // Add qualified names from fixes
        qualifiedNames.addAll(fixes.values());
        return new FixImports(root, trees.getSourcePositions(), qualifiedNames);
    }

    private static final Logger LOG = Logger.getLogger("main");
}
