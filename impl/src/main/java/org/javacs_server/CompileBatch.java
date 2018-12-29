package org.javacs_server;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.lang.model.element.*;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.javacs.lsp.Range;


public class CompileBatch {
    private final JavaCompilerService parent;
    private final JavacTask task;
    private final Trees trees;
    private final List<CompilationUnitTree> roots;

    CompileBatch(JavaCompilerService parent, Collection<URI> files) {
        this.parent = parent;
        this.task = batchTask(parent, files);
        this.trees = Trees.instance(task);
        this.roots = new ArrayList<CompilationUnitTree>();
        var profiler = new Profiler();
        task.addTaskListener(profiler);
        try {
            for (var t : task.parse()) roots.add(t);
            task.analyze();
            // The results of task.analyze() are unreliable when errors are present
            // You can get at `Element` values using `Trees`
            task.analyze();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        profiler.print();
    }

    static JavacTask batchTask(JavaCompilerService parent, Collection<URI> paths) {
        parent.diags.clear();
        var files = new ArrayList<File>();
        for (var p : paths) files.add(new File(p));
        return (JavacTask)
                parent.compiler.getTask(
                        null,
                        parent.fileManager,
                        parent.diags::add,
                        JavaCompilerService.options(parent.sourcePath, parent.classPath),
                        Collections.emptyList(),
                        parent.fileManager.getJavaFileObjectsFromFiles(files));
    }

    public List<Diagnostic<? extends JavaFileObject>> lint() {
        return Collections.unmodifiableList(new ArrayList<>(parent.diags));
    }

    public List<TreePath> references(Element to) {
        LOG.info(String.format("Search for references to `%s` in %d files...", to, roots.size()));

        var result = new ArrayList<TreePath>();
        for (var f : roots) {
            result.addAll(referencesToElement(f, to));
        }
        return result;
    }

    public Map<URI, Index> countReferences() {
        var index = new HashMap<URI, Index>();
        for (var f : roots) {
            var uri = f.getSourceFile().toUri();
            var path = Paths.get(uri);
            var refs = index(f);
            FileTime modified;
            try {
                modified = Files.getLastModifiedTime(path);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            var i = new Index(refs, modified.toInstant());
            index.put(uri, i);
        }
        return index;
    }

    public Optional<Range> range(TreePath path) {
        var uri = path.getCompilationUnit().getSourceFile().toUri();
        var file = Paths.get(uri);
        String contents;
        try {
            contents = Files.readAllLines(file).stream().collect(Collectors.joining("\n"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return ParseFile.range(task, contents, path);
    }

    private boolean toStringEquals(Object left, Object right) {
        return Objects.equals(Objects.toString(left, ""), Objects.toString(right, ""));
    }

    private boolean sameSymbol(Element from, Element to) {
        return to != null
                && from != null
                && toStringEquals(to.getEnclosingElement(), from.getEnclosingElement())
                && toStringEquals(to, from);
    }

    private boolean isField(Element to) {
        if (!(to instanceof VariableElement)) return false;
        var field = (VariableElement) to;
        return field.getEnclosingElement() instanceof TypeElement;
    }

    private Optional<TreePath> ref(TreePath from) {
        var root = from.getCompilationUnit();
        var lines = root.getLineMap();
        var to = trees.getElement(from);
        // Skip elements we can't find
        if (to == null) {
            // LOG.warning(String.format("No element for `%s`", from.getLeaf()));
            return Optional.empty();
        }
        // Skip non-methods
        if (!(to instanceof ExecutableElement || to instanceof TypeElement || isField(to))) {
            return Optional.empty();
        }
        // TODO skip anything not on source path
        var result = trees.getPath(to);
        if (result == null) {
            // LOG.warning(String.format("Element `%s` has no TreePath", to));
            return Optional.empty();
        }
        return Optional.of(result);
    }

    private List<TreePath> referencesToElement(CompilationUnitTree root, Element to) {
        var trees = Trees.instance(task);
        var results = new ArrayList<TreePath>();
        class FindReferencesElement extends TreePathScanner<Void, Void> {
            void check(TreePath from) {
                var found = trees.getElement(from);
                if (sameSymbol(found, to)) {
                    results.add(from);
                }
            }

            @Override
            public Void visitMemberReference(MemberReferenceTree t, Void __) {
                check(getCurrentPath());
                return super.visitMemberReference(t, null);
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree t, Void __) {
                check(getCurrentPath());
                return super.visitMemberSelect(t, null);
            }

            @Override
            public Void visitIdentifier(IdentifierTree t, Void __) {
                check(getCurrentPath());
                return super.visitIdentifier(t, null);
            }

            @Override
            public Void visitNewClass(NewClassTree t, Void __) {
                check(getCurrentPath());
                return super.visitNewClass(t, null);
            }
        }
        new FindReferencesElement().scan(root, null);
        LOG.info(
                String.format(
                        "...found %d references in %s", results.size(), Parser.fileName(root.getSourceFile().toUri())));
        return results;
    }

    private List<Ptr> index(CompilationUnitTree root) {
        var refs = new ArrayList<Ptr>();
        class IndexFile extends TreePathScanner<Void, Void> {
            void check(TreePath from) {
                var r = ref(from);
                if (r.isPresent()) {
                    var ptr = new Ptr(r.get());
                    refs.add(ptr);
                }
            }

            @Override
            public Void visitMemberReference(MemberReferenceTree t, Void __) {
                check(getCurrentPath());
                return super.visitMemberReference(t, null);
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree t, Void __) {
                check(getCurrentPath());
                return super.visitMemberSelect(t, null);
            }

            @Override
            public Void visitIdentifier(IdentifierTree t, Void __) {
                check(getCurrentPath());
                return super.visitIdentifier(t, null);
            }

            @Override
            public Void visitNewClass(NewClassTree t, Void __) {
                check(getCurrentPath());
                return super.visitNewClass(t, null);
            }
        }
        new IndexFile().scan(root, null);
        if (refs.size() > 0) {
            var n = refs.size();
            var file = Parser.fileName(root.getSourceFile().toUri());
            LOG.info(String.format("Found %d refs in %s", n, file));
        }
        return refs;
    }

    private static final Logger LOG = Logger.getLogger("main");
}
