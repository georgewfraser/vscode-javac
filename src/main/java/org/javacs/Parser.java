package org.javacs;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.lang.model.element.*;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import org.javacs.lsp.*;

class Parser {
    private static final JavaCompiler COMPILER = ServiceLoader.load(JavaCompiler.class).iterator().next();
    private static final StandardJavaFileManager FILE_MANAGER =
            COMPILER.getStandardFileManager(Parser::ignoreError, null, null);

    /** Create a task that compiles a single file */
    private static JavacTask singleFileTask(JavaFileObject file) {
        return (JavacTask)
                COMPILER.getTask(null, FILE_MANAGER, Parser::ignoreError, List.of(), List.of(), List.of(file));
    }

    private final String contents;
    private final JavacTask task;
    private final CompilationUnitTree root;

    private Parser(URI file) {
        this(new SourceFileObject(file));
    }

    private Parser(JavaFileObject file) {
        Objects.requireNonNull(file);

        try {
            this.contents = file.getCharContent(false).toString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.task = singleFileTask(file);
        try {
            this.root = task.parse().iterator().next();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static Parser parseFile(URI file) {
        return new Parser(file);
    }

    static Parser parseJavaFileObject(JavaFileObject file) {
        return new Parser(file);
    }

    boolean isTestMethod(TreePath path) {
        var leaf = path.getLeaf();
        if (!(leaf instanceof MethodTree)) return false;
        var method = (MethodTree) leaf;
        for (var ann : method.getModifiers().getAnnotations()) {
            var type = ann.getAnnotationType();
            if (type instanceof IdentifierTree) {
                var id = (IdentifierTree) type;
                var name = id.getName();
                if (name.contentEquals("Test") || name.contentEquals("org.junit.Test")) {
                    return true;
                }
            }
        }
        return false;
    }

    boolean isTestClass(TreePath path) {
        var leaf = path.getLeaf();
        if (!(leaf instanceof ClassTree)) return false;
        var cls = (ClassTree) leaf;
        for (var m : cls.getMembers()) {
            if (isTestMethod(new TreePath(path, m))) return true;
        }
        return false;
    }

    boolean isOverride(TreePath path) {
        var leaf = path.getLeaf();
        if (!(leaf instanceof MethodTree)) return false;
        var method = (MethodTree) leaf;
        for (var ann : method.getModifiers().getAnnotations()) {
            var type = ann.getAnnotationType();
            if (type instanceof IdentifierTree) {
                var id = (IdentifierTree) type;
                var name = id.getName();
                if (name.contentEquals("Override")) {
                    return true;
                }
            }
        }
        return false;
    }

    List<TreePath> declarations() {
        return declarations(root);
    }

    static List<TreePath> declarations(CompilationUnitTree root) {
        var found = new ArrayList<TreePath>();
        class FindDeclarations extends TreePathScanner<Void, Void> {
            boolean isClass(Tree t) {
                if (!(t instanceof ClassTree)) return false;
                var cls = (ClassTree) t;
                return cls.getKind() == Tree.Kind.CLASS;
            }

            boolean isPrivate(ModifiersTree t) {
                return t.getFlags().contains(Modifier.PRIVATE);
            }

            @Override
            public Void visitClass(ClassTree t, Void __) {
                if (isPrivate(t.getModifiers())) return null;
                found.add(getCurrentPath());
                return super.visitClass(t, null);
            }

            @Override
            public Void visitMethod(MethodTree t, Void __) {
                if (isPrivate(t.getModifiers())) return null;
                var path = getCurrentPath();
                found.add(path);
                // Skip code lenses for local classes
                return null;
            }

            @Override
            public Void visitVariable(VariableTree t, Void __) {
                if (isPrivate(t.getModifiers())) return null;
                var path = getCurrentPath();
                var parent = path.getParentPath().getLeaf();
                if (isClass(parent)) {
                    found.add(path);
                }
                // Skip code lenses for local classes
                return null;
            }
        }
        new FindDeclarations().scan(root, null);

        return found;
    }

    Optional<Range> range(TreePath path) {
        return range(task, contents, path);
    }

    Optional<Location> location(TreePath path) {
        var uri = root.getSourceFile().toUri();
        return range(task, contents, path).map(range -> new Location(uri, range));
    }

    Optional<CompletionContext> completionContext(int line, int character) {
        LOG.info(
                String.format(
                        "Finding completion position near %s(%d,%d)...",
                        root.getSourceFile().toUri().getPath(), line, character));

        var trees = Trees.instance(task);
        var pos = trees.getSourcePositions();
        var lines = root.getLineMap();
        var cursor = lines.getPosition(line, character);

        class FindCompletionPosition extends TreeScanner<Void, Void> {
            CompletionContext result = null;
            int insideClass = 0, insideMethod = 0;

            boolean containsCursor(Tree node) {
                return pos.getStartPosition(root, node) <= cursor && cursor <= pos.getEndPosition(root, node);
            }

            @Override
            public Void visitClass(ClassTree node, Void nothing) {
                insideClass++;
                super.visitClass(node, null);
                insideClass--;
                return null;
            }

            @Override
            public Void visitMethod(MethodTree node, Void nothing) {
                insideMethod++;
                super.visitMethod(node, null);
                insideMethod--;
                return null;
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree node, Void nothing) {
                super.visitMemberSelect(node, nothing);

                if (containsCursor(node) && !containsCursor(node.getExpression()) && result == null) {
                    LOG.info("...position cursor before '.' in " + node);
                    long offset = pos.getEndPosition(root, node.getExpression());
                    int line = (int) lines.getLineNumber(offset), character = (int) lines.getColumnNumber(offset);
                    var partialName = Objects.toString(node.getIdentifier(), "");
                    result =
                            new CompletionContext(
                                    line,
                                    character,
                                    insideClass > 0,
                                    insideMethod > 0,
                                    CompletionContext.Kind.MemberSelect,
                                    partialName);
                }
                return null;
            }

            @Override
            public Void visitMemberReference(MemberReferenceTree node, Void nothing) {
                super.visitMemberReference(node, nothing);

                if (containsCursor(node) && !containsCursor(node.getQualifierExpression()) && result == null) {
                    LOG.info("...position cursor before '::' in " + node);
                    long offset = pos.getEndPosition(root, node.getQualifierExpression());
                    int line = (int) lines.getLineNumber(offset), character = (int) lines.getColumnNumber(offset);
                    var partialName = Objects.toString(node.getName(), "");
                    result =
                            new CompletionContext(
                                    line,
                                    character,
                                    insideClass > 0,
                                    insideMethod > 0,
                                    CompletionContext.Kind.MemberReference,
                                    partialName);
                }
                return null;
            }

            @Override
            public Void visitCase(CaseTree node, Void nothing) {
                var containsCursor = containsCursor(node);
                for (var s : node.getStatements()) {
                    if (containsCursor(s)) containsCursor = false;
                }

                if (containsCursor) {
                    LOG.info("...position cursor after case " + node.getExpression());
                    long offset = pos.getEndPosition(root, node.getExpression());
                    int line = (int) lines.getLineNumber(offset), character = (int) lines.getColumnNumber(offset);
                    var partialName = Objects.toString(node.getExpression(), "");
                    result =
                            new CompletionContext(
                                    line,
                                    character,
                                    insideClass > 0,
                                    insideMethod > 0,
                                    CompletionContext.Kind.Case,
                                    partialName);
                } else {
                    super.visitCase(node, nothing);
                }
                return null;
            }

            @Override
            public Void visitIdentifier(IdentifierTree node, Void nothing) {
                super.visitIdentifier(node, nothing);

                if (containsCursor(node) && result == null) {
                    LOG.info("...position cursor after identifier " + node.getName());
                    var partialName = Objects.toString(node.getName(), "");
                    result =
                            new CompletionContext(
                                    line,
                                    character,
                                    insideClass > 0,
                                    insideMethod > 0,
                                    CompletionContext.Kind.Identifier,
                                    partialName);
                }
                return null;
            }

            @Override
            public Void visitAnnotation(AnnotationTree node, Void nothing) {
                if (containsCursor(node.getAnnotationType()) && result == null) {
                    LOG.info("...position cursor after annotation " + node.getAnnotationType());
                    var id = (IdentifierTree) node.getAnnotationType();
                    var partialName = Objects.toString(id.getName(), "");
                    result =
                            new CompletionContext(
                                    line,
                                    character,
                                    insideClass > 0,
                                    insideMethod > 0,
                                    CompletionContext.Kind.Annotation,
                                    partialName);
                } else {
                    super.visitAnnotation(node, nothing);
                }
                return null;
            }

            @Override
            public Void visitErroneous(ErroneousTree node, Void nothing) {
                for (var t : node.getErrorTrees()) {
                    t.accept(this, null);
                }
                return null;
            }
        }
        var find = new FindCompletionPosition();
        find.scan(root, null);
        if (find.result == null) {
            LOG.info("...found nothing near cursor!");
            return Optional.empty();
        }
        return Optional.of(find.result);
    }

    List<FoldingRange> foldingRanges() {
        var imports = new ArrayList<TreePath>();
        var blocks = new ArrayList<TreePath>();
        // TODO find comment trees
        var comments = new ArrayList<TreePath>();
        class FindFoldingRanges extends TreePathScanner<Void, Void> {
            @Override
            public Void visitClass(ClassTree t, Void __) {
                blocks.add(getCurrentPath());
                return super.visitClass(t, null);
            }

            @Override
            public Void visitBlock(BlockTree t, Void __) {
                blocks.add(getCurrentPath());
                return super.visitBlock(t, null);
            }

            @Override
            public Void visitImport(ImportTree t, Void __) {
                imports.add(getCurrentPath());
                return null;
            }
        }
        new FindFoldingRanges().scan(root, null);

        var all = new ArrayList<FoldingRange>();

        // Merge import ranges
        if (!imports.isEmpty()) {
            var merged = asFoldingRange(imports.get(0), FoldingRangeKind.Imports);
            for (var i : imports) {
                var r = asFoldingRange(i, FoldingRangeKind.Imports);
                if (r.startLine <= merged.endLine + 1) {
                    merged =
                            new FoldingRange(
                                    merged.startLine,
                                    merged.startCharacter,
                                    r.endLine,
                                    r.endCharacter,
                                    FoldingRangeKind.Imports);
                } else {
                    all.add(merged);
                    merged = r;
                }
            }
            all.add(merged);
        }

        // Convert blocks and comments
        for (var t : blocks) {
            all.add(asFoldingRange(t, FoldingRangeKind.Region));
        }
        for (var t : comments) {
            all.add(asFoldingRange(t, FoldingRangeKind.Region));
        }

        return all;
    }

    private FoldingRange asFoldingRange(TreePath t, String kind) {
        var trees = Trees.instance(task);
        var pos = trees.getSourcePositions();
        var lines = t.getCompilationUnit().getLineMap();
        var start = (int) pos.getStartPosition(t.getCompilationUnit(), t.getLeaf());
        var end = (int) pos.getEndPosition(t.getCompilationUnit(), t.getLeaf());

        // If this is a class tree, adjust start position to '{'
        if (t.getLeaf() instanceof ClassTree) {
            CharSequence content;
            try {
                content = t.getCompilationUnit().getSourceFile().getCharContent(true);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            for (var i = start; i < content.length(); i++) {
                if (content.charAt(i) == '{') {
                    start = i;
                    break;
                }
            }
        }

        // Convert offset to 0-based line and character
        var startLine = (int) lines.getLineNumber(start) - 1; // TODO (int) is not coloring
        var startChar = (int) lines.getColumnNumber(start) - 1;
        var endLine = (int) lines.getLineNumber(end) - 1;
        var endChar = (int) lines.getColumnNumber(end) - 1;

        // If this is a block, move end position back one line so we don't fold the '}'
        if (t.getLeaf() instanceof ClassTree || t.getLeaf() instanceof BlockTree) {
            endLine--;
        }

        return new FoldingRange(startLine, startChar, endLine, endChar, kind);
    }

    /** Find and source code associated with a ptr */
    Optional<TreePath> fuzzyFind(Ptr ptr) {
        LOG.info(
                String.format(
                        "...find fuzzy match of %s in %s ...",
                        ptr, StringSearch.fileName(root.getSourceFile().toUri())));

        class FindPtr extends TreePathScanner<Void, Void> {
            int bestMatch = Ptr.NOT_MATCHED;
            TreePath found;

            void check() {
                var path = getCurrentPath();
                var mismatch = ptr.fuzzyMatch(path);
                if (mismatch < bestMatch) {
                    found = path;
                    bestMatch = mismatch;
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
                // Ptr can't point inside a method
                return null;
            }

            @Override
            public Void visitVariable(VariableTree node, Void aVoid) {
                check();
                // Ptr can't point inside a field
                return null;
            }
        }
        var find = new FindPtr();
        find.scan(root, null);
        if (find.found != null)
            LOG.info(
                    String.format(
                            "...`%s` with score %d is best match", describeTree(find.found.getLeaf()), find.bestMatch));
        else LOG.info("...no match found");
        return Optional.ofNullable(find.found);
    }

    DocCommentTree doc(TreePath path) {
        // Find ptr in the file
        // Find the documentation attached to el
        var docs = DocTrees.instance(task);
        var doc = docs.getDocCommentTree(path);
        if (doc == null) return EMPTY_DOC;
        return doc;
    }

    static Optional<Range> range(JavacTask task, String contents, TreePath path) {
        // Find start position
        var trees = Trees.instance(task);
        var pos = trees.getSourcePositions();
        var root = path.getCompilationUnit();
        var lines = root.getLineMap();
        var start = (int) pos.getStartPosition(root, path.getLeaf());
        var end = (int) pos.getEndPosition(root, path.getLeaf());

        // If start is -1, give up
        if (start == -1) {
            LOG.warning(String.format("Couldn't locate `%s`", path.getLeaf()));
            return Optional.empty();
        }
        // If end is bad, guess based on start
        if (end == -1) {
            end = start + path.getLeaf().toString().length();
        }

        if (path.getLeaf() instanceof ClassTree) {
            var cls = (ClassTree) path.getLeaf();

            // If class has annotations, skip over them
            if (!cls.getModifiers().getAnnotations().isEmpty())
                start = (int) pos.getEndPosition(root, cls.getModifiers());

            // Find position of class name
            var name = cls.getSimpleName().toString();
            start = contents.indexOf(name, start);
            if (start == -1) {
                LOG.warning(String.format("Couldn't find identifier `%s` in `%s`", name, path.getLeaf()));
                return Optional.empty();
            }
            end = start + name.length();
        }
        if (path.getLeaf() instanceof MethodTree) {
            var method = (MethodTree) path.getLeaf();

            // If method has annotations, skip over them
            if (!method.getModifiers().getAnnotations().isEmpty())
                start = (int) pos.getEndPosition(root, method.getModifiers());

            // Find position of method name
            var name = method.getName().toString();
            if (name.equals("<init>")) {
                name = JavaCompilerService.className(path);
            }
            start = contents.indexOf(name, start);
            if (start == -1) {
                LOG.warning(String.format("Couldn't find identifier `%s` in `%s`", name, path.getLeaf()));
                return Optional.empty();
            }
            end = start + name.length();
        }
        if (path.getLeaf() instanceof VariableTree) {
            var field = (VariableTree) path.getLeaf();

            // If field has annotations, skip over them
            if (!field.getModifiers().getAnnotations().isEmpty())
                start = (int) pos.getEndPosition(root, field.getModifiers());

            // Find position of method name
            var name = field.getName().toString();
            start = contents.indexOf(name, start);
            if (start == -1) {
                LOG.warning(String.format("Couldn't find identifier `%s` in `%s`", name, path.getLeaf()));
                return Optional.empty();
            }
            end = start + name.length();
        }
        if (path.getLeaf() instanceof MemberSelectTree) {
            var member = (MemberSelectTree) path.getLeaf();
            var name = member.getIdentifier().toString();
            start = contents.indexOf(name, start);
            if (start == -1) {
                LOG.warning(String.format("Couldn't find identifier `%s` in `%s`", name, path.getLeaf()));
                return Optional.empty();
            }
            end = start + name.length();
        }
        var startLine = (int) lines.getLineNumber(start);
        var startCol = (int) lines.getColumnNumber(start);
        var endLine = (int) lines.getLineNumber(end);
        var endCol = (int) lines.getColumnNumber(end);
        var range = new Range(new Position(startLine - 1, startCol - 1), new Position(endLine - 1, endCol - 1));

        return Optional.of(range);
    }

    List<SymbolInformation> documentSymbols() {
        return findSymbolsMatching("");
    }

    List<SymbolInformation> findSymbolsMatching(String query) {
        List<TreePath> found = new ArrayList<>();
        class Find extends TreePathScanner<Void, Void> {
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

            void run() {
                scan(root, null);
            }
        }
        new Find().run();
        var symbols = new ArrayList<SymbolInformation>();
        for (var path : found) {
            asSymbolInformation(path, symbols);
        }
        return symbols;
    }

    private void asSymbolInformation(TreePath path, List<SymbolInformation> acc) {
        var l = location(path);
        if (l.isEmpty()) return;
        var i = new SymbolInformation();
        var t = path.getLeaf();
        i.kind = asSymbolKind(t.getKind());
        i.name = symbolName(t);
        i.containerName = containerName(path);
        i.location = l.get();
        acc.add(i);
    }

    private static Integer asSymbolKind(Tree.Kind k) {
        switch (k) {
            case ANNOTATION_TYPE:
            case CLASS:
                return SymbolKind.Class;
            case ENUM:
                return SymbolKind.Enum;
            case INTERFACE:
                return SymbolKind.Interface;
            case METHOD:
                return SymbolKind.Method;
            case TYPE_PARAMETER:
                return SymbolKind.TypeParameter;
            case VARIABLE:
                // This method is used for symbol-search functionality,
                // where we only return fields, not local variables
                return SymbolKind.Field;
            default:
                return null;
        }
    }

    private static String containerName(TreePath path) {
        var parent = path.getParentPath();
        while (parent != null) {
            var t = parent.getLeaf();
            if (t instanceof ClassTree) {
                var c = (ClassTree) t;
                return c.getSimpleName().toString();
            } else if (t instanceof CompilationUnitTree) {
                var c = (CompilationUnitTree) t;
                return Objects.toString(c.getPackageName(), "");
            } else {
                parent = parent.getParentPath();
            }
        }
        return null;
    }

    private static String symbolName(Tree t) {
        if (t instanceof ClassTree) {
            var c = (ClassTree) t;
            return c.getSimpleName().toString();
        } else if (t instanceof MethodTree) {
            var m = (MethodTree) t;
            return m.getName().toString();
        } else if (t instanceof VariableTree) {
            var v = (VariableTree) t;
            return v.getName().toString();
        } else {
            LOG.warning("Don't know how to create SymbolInformation from " + t);
            return "???";
        }
    }

    private static final DocCommentTree EMPTY_DOC = makeEmptyDoc();

    private static DocCommentTree makeEmptyDoc() {
        var file = new SourceFileObject(URI.create("file:///Foo.java"), "/** */ class Foo { }", Instant.now());
        var fileManager = COMPILER.getStandardFileManager(Parser::ignoreError, null, Charset.defaultCharset());
        var task =
                (JavacTask)
                        COMPILER.getTask(
                                null,
                                fileManager,
                                Parser::ignoreError,
                                List.of(),
                                null,
                                Collections.singletonList(file));
        var docs = DocTrees.instance(task);
        CompilationUnitTree root;
        try {
            root = task.parse().iterator().next();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        class FindEmptyDoc extends TreePathScanner<Void, Void> {
            DocCommentTree found;

            @Override
            public Void visitClass(ClassTree t, Void __) {
                found = docs.getDocCommentTree(getCurrentPath());
                return null;
            }
        }
        var find = new FindEmptyDoc();
        find.scan(root, null);
        return Objects.requireNonNull(find.found);
    }

    private static void ignoreError(javax.tools.Diagnostic<? extends JavaFileObject> err) {
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

    List<String> accessibleClasses(String partialName, String fromPackage) {
        var toPackage = Objects.toString(root.getPackageName(), "");
        var samePackage = fromPackage.equals(toPackage) || toPackage.isEmpty();
        var result = new ArrayList<String>();
        for (var t : root.getTypeDecls()) {
            if (!(t instanceof ClassTree)) continue;
            var cls = (ClassTree) t;
            // If class is not accessible, skip it
            var isPublic = cls.getModifiers().getFlags().contains(Modifier.PUBLIC);
            if (!samePackage && !isPublic) continue;
            // If class doesn't match partialName, skip it
            var name = cls.getSimpleName().toString();
            if (!StringSearch.matchesPartialName(name, partialName)) continue;
            if (root.getPackageName() != null) {
                name = root.getPackageName() + "." + name;
            }
            result.add(name);
        }
        return result;
    }

    boolean mightReference(Element to) {
        var findName = simpleName(to);
        var isField = to instanceof VariableElement && to.getEnclosingElement() instanceof TypeElement;
        var isType = to instanceof TypeElement;
        class Found extends RuntimeException {}
        if (isField || isType) {
            LOG.info(String.format("...find identifiers named `%s`", findName));
            class FindId extends TreePathScanner<Void, Void> {
                boolean method() {
                    var leaf = getCurrentPath().getLeaf();
                    var parent = getCurrentPath().getParentPath().getLeaf();
                    if (parent instanceof MethodInvocationTree) {
                        var method = (MethodInvocationTree) parent;
                        return method.getMethodSelect() == leaf;
                    }
                    return false;
                }

                @Override
                public Void visitIdentifier(IdentifierTree t, Void __) {
                    // TODO try to disprove that this is a reference by looking at obvious special cases, like is the
                    // simple name of the type different?
                    if (t.getName().contentEquals(findName) && !method()) {
                        throw new Found();
                    }
                    return super.visitIdentifier(t, null);
                }

                @Override
                public Void visitMemberSelect(MemberSelectTree t, Void __) {
                    if (t.getIdentifier().contentEquals(findName) && !method()) {
                        throw new Found();
                    }
                    return super.visitMemberSelect(t, null);
                }
            }
            try {
                new FindId().scan(root, null);
            } catch (Found __) {
                return true;
            }
            return false;
        } else if (to instanceof ExecutableElement) {
            LOG.info(String.format("...find method calls named `%s`", findName));
            class FindMethod extends TreePathScanner<Void, Void> {
                boolean found = false;

                boolean isName(Tree t) {
                    if (t instanceof MemberSelectTree) {
                        var select = (MemberSelectTree) t;
                        return select.getIdentifier().contentEquals(findName);
                    }
                    if (t instanceof IdentifierTree) {
                        var id = (IdentifierTree) t;
                        return id.getName().contentEquals(findName);
                    }
                    if (t instanceof ParameterizedTypeTree) {
                        var param = (ParameterizedTypeTree) t;
                        return isName(param.getType());
                    }
                    return false;
                }

                @Override
                public Void visitMethodInvocation(MethodInvocationTree t, Void __) {
                    // TODO try to disprove that this is a reference by looking at obvious special cases, like is the
                    // simple name of the type different?
                    var method = t.getMethodSelect();
                    if (isName(method)) {
                        throw new Found();
                    }
                    // Check other parts
                    return super.visitMethodInvocation(t, null);
                }

                @Override
                public Void visitMemberReference(MemberReferenceTree t, Void __) {
                    if (t.getName().contentEquals(findName)) {
                        throw new Found();
                    }
                    return super.visitMemberReference(t, null);
                }

                @Override
                public Void visitNewClass(NewClassTree t, Void __) {
                    var cls = t.getIdentifier();
                    if (isName(cls)) {
                        throw new Found();
                    }
                    return super.visitNewClass(t, null);
                }
            }
            try {
                new FindMethod().scan(root, null);
            } catch (Found __) {
                return true;
            }
            return false;
        } else {
            return false;
        }
    }

    boolean mightContainDefinition(Element to) {
        var findName = simpleName(to);
        class Found extends RuntimeException {}
        class FindMethod extends TreePathScanner<Void, Void> {
            private Name className;

            @Override
            public Void visitClass(ClassTree t, Void __) {
                var prev = className;
                className = t.getSimpleName();
                super.visitClass(t, null);
                className = prev;
                return null;
            }

            @Override
            public Void visitMethod(MethodTree t, Void __) {
                // TODO try to disprove that this is a reference by looking at obvious special cases, like is the
                // simple name of the type different?
                var match =
                        t.getName().contentEquals(findName)
                                || t.getName().contentEquals("<init>") && className.contentEquals(findName);
                if (match) {
                    throw new Found();
                }
                return super.visitMethod(t, null);
            }
        }
        try {
            new FindMethod().scan(root, null);
        } catch (Found __) {
            return true;
        }
        return false;
    }

    static CharSequence simpleName(Element e) {
        if (e.getSimpleName().contentEquals("<init>")) {
            return e.getEnclosingElement().getSimpleName();
        }
        return e.getSimpleName();
    }

    private static String prune(CompilationUnitTree root, SourcePositions pos, StringBuilder buffer, long[] offsets) {
        class Scan extends TreeScanner<Void, Void> {
            boolean erasedAfterCursor = false;

            boolean containsCursor(Tree node) {
                var start = pos.getStartPosition(root, node);
                var end = pos.getEndPosition(root, node);
                for (var cursor : offsets) {
                    if (start <= cursor && cursor <= end) {
                        return true;
                    }
                }
                return false;
            }

            boolean anyContainsCursor(Collection<? extends Tree> nodes) {
                for (var n : nodes) {
                    if (containsCursor(n)) return true;
                }
                return false;
            }

            long lastCursorIn(Tree node) {
                var start = pos.getStartPosition(root, node);
                var end = pos.getEndPosition(root, node);
                long last = -1;
                for (var cursor : offsets) {
                    if (start <= cursor && cursor <= end) {
                        last = cursor;
                    }
                }
                if (last == -1) {
                    throw new RuntimeException(
                            String.format("No cursor in %s is between %d and %d", offsets, start, end));
                }
                return last;
            }

            void erase(long start, long end) {
                for (int i = (int) start; i < end; i++) {
                    switch (buffer.charAt(i)) {
                        case '\r':
                        case '\n':
                            break;
                        default:
                            buffer.setCharAt(i, ' ');
                    }
                }
            }

            @Override
            public Void visitImport(ImportTree node, Void __) {
                // Erase 'static' keyword so autocomplete works better
                if (containsCursor(node) && node.isStatic()) {
                    var start = (int) pos.getStartPosition(root, node);
                    start = buffer.indexOf("static", start);
                    var end = start + "static".length();
                    erase(start, end);
                }
                return super.visitImport(node, null);
            }

            @Override
            public Void visitSwitch(SwitchTree node, Void __) {
                // If cursor is in a case label, for example
                //   case F|
                // then erase the entire contents of the switch statement.
                for (var c : node.getCases()) {
                    if (containsCursor(c) && !anyContainsCursor(c.getStatements())) {
                        eraseSwitchContents(node);
                        erasedAfterCursor = true;
                        return null;
                    }
                }
                return super.visitSwitch(node, null);
            }

            private void eraseSwitchContents(SwitchTree node) {
                for (var c : node.getCases()) {
                    var start = (int) pos.getStartPosition(root, c);
                    var end = (int) pos.getEndPosition(root, c);
                    erase(start, end);
                }
            }

            @Override
            public Void visitBlock(BlockTree node, Void __) {
                if (containsCursor(node)) {
                    super.visitBlock(node, null);
                    // When we find the deepest block that includes the cursor
                    if (!erasedAfterCursor) {
                        var cursor = lastCursorIn(node);
                        var start = cursor;
                        var end = pos.getEndPosition(root, node);
                        if (end >= buffer.length()) end = buffer.length() - 1;
                        // Find the next line
                        while (start < end && buffer.charAt((int) start) != '\n') start++;
                        // Find the end of the block
                        while (end > start && buffer.charAt((int) end) != '}') end--;
                        // Erase from next line to end of block
                        erase(start, end - 1);
                        erasedAfterCursor = true;
                    }
                } else if (!node.getStatements().isEmpty()) {
                    var first = node.getStatements().get(0);
                    var last = node.getStatements().get(node.getStatements().size() - 1);
                    var start = pos.getStartPosition(root, first);
                    var end = pos.getEndPosition(root, last);
                    if (end >= buffer.length()) end = buffer.length() - 1;
                    erase(start, end);
                }
                return null;
            }

            @Override
            public Void visitErroneous(ErroneousTree node, Void nothing) {
                return super.scan(node.getErrorTrees(), nothing);
            }
        }

        new Scan().scan(root, null);

        return buffer.toString();
    }

    String prune(int line, int character) {
        // Erase all blocks that don't include line:character
        var file = root.getSourceFile().toUri();
        var lines = root.getLineMap();
        var cursor = lines.getPosition(line, character);
        var pos = Trees.instance(task).getSourcePositions();
        var contents = FileStore.contents(file);
        var buffer = new StringBuilder(contents);
        return prune(root, pos, buffer, new long[] {cursor});
    }

    String prune(String name) {
        // Find all occurrences of name in contents
        var file = root.getSourceFile().toUri();
        var contents = FileStore.contents(file);
        var list = new ArrayList<Long>();
        var pattern = Pattern.compile("\\b" + Pattern.quote(name) + "\\b");
        var matcher = pattern.matcher(contents);
        while (matcher.find()) {
            list.add((long) matcher.start());
        }
        var offsets = new long[list.size()];
        for (var i = 0; i < list.size(); i++) {
            offsets[i] = list.get(i);
        }
        // Erase all blocks that don't contain name
        var buffer = new StringBuilder(contents);
        var pos = Trees.instance(task).getSourcePositions();
        return prune(root, pos, buffer, offsets);
    }

    private static final Logger LOG = Logger.getLogger("main");
}

class CompletionContext {
    // 1-based
    final int line, character;
    final boolean inClass, inMethod;
    final Kind kind;
    final String partialName;

    CompletionContext(int line, int character, boolean inClass, boolean inMethod, Kind kind, String partialName) {
        this.line = line;
        this.character = character;
        this.inClass = inClass;
        this.inMethod = inMethod;
        this.kind = kind;
        this.partialName = partialName;
    }

    enum Kind {
        MemberSelect,
        MemberReference,
        Identifier,
        Annotation,
        Case,
    }
}
