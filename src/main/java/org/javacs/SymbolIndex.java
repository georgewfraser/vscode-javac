/*
 * Original work Copyright (c) 2017 George W Fraser.
 * Modified work Copyright (c) 2017 Palantir Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 */

package org.javacs;

import com.google.common.collect.Maps;
import com.sun.source.tree.*;
import com.sun.source.util.*;
import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.file.JavacFileManager;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Stream;
import javax.lang.model.element.*;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.eclipse.lsp4j.*;

/**
 * Global index of exported symbol declarations and references. such as classes, methods, and
 * fields.
 */
class SymbolIndex {

    private final Path workspaceRoot;
    private final Supplier<Collection<URI>> openFiles;
    private final Function<URI, Optional<String>> activeContent;
    private final JavacTool parser = JavacTool.create();
    private final JavacFileManager emptyFileManager =
            parser.getStandardFileManager(__ -> {}, null, Charset.defaultCharset());

    /** Source path files, for which we support methods and classes */
    private final Map<URI, SourceFileIndex> sourcePathFiles = new ConcurrentHashMap<>();

    private final CompletableFuture<?> finishedInitialIndex = new CompletableFuture<>();

    SymbolIndex(
            Path workspaceRoot,
            Supplier<Collection<URI>> openFiles,
            Function<URI, Optional<String>> activeContent) {
        this.workspaceRoot = workspaceRoot;
        this.openFiles = openFiles;
        this.activeContent = activeContent;

        new Thread(this::initialIndex, "Initial-Index").start();
    }

    private void initialIndex() {
        // TODO send a progress bar to the user
        updateIndex(InferConfig.allJavaFiles(workspaceRoot).map(Path::toUri));

        finishedInitialIndex.complete(null);
    }

    private void updateIndex(Stream<URI> files) {
        files.forEach(this::updateFile);
    }

    private void updateFile(URI each) {
        if (needsUpdate(each)) {
            CompilationUnitTree tree = parse(each);

            update(tree);
        }
    }

    private boolean needsUpdate(URI file) {
        if (!sourcePathFiles.containsKey(file)) return true;
        else {
            try {
                Instant updated = sourcePathFiles.get(file).updated;
                Instant modified = Files.getLastModifiedTime(Paths.get(file)).toInstant();

                return updated.isBefore(modified);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private final Map<URI, String> warnedPackageDirectoryConflict = new HashMap<>();

    /**
     * Guess the source path by looking at package declarations in .java files.
     *
     * <p>For example, if the file src/com/example/Test.java has the package declaration `package
     * com.example;` then the source root is `src`.
     */
    Set<Path> sourcePath() {
        updateOpenFiles();

        Set<Path> result = new HashSet<>();

        sourcePathFiles.forEach(
                (uri, index) -> {
                    Path dir = Paths.get(uri).getParent();
                    String packagePath = index.packageName.replace('.', File.separatorChar);

                    if (!dir.endsWith(packagePath)
                            && !warnedPackageDirectoryConflict
                                    .getOrDefault(uri, "?")
                                    .equals(packagePath)) {
                        LOG.warning("Java source file " + uri + " is not in " + packagePath);

                        warnedPackageDirectoryConflict.put(uri, packagePath);
                    } else {
                        int up = Paths.get(packagePath).getNameCount();
                        Path truncate = dir;

                        for (int i = 0; i < up; i++) truncate = truncate.getParent();

                        result.add(truncate);
                    }
                });

        return result;
    }

    /** Search all indexed symbols */
    Stream<SymbolInformation> search(String query) {
        updateOpenFiles();

        Predicate<CharSequence> nameMatchesQuery =
                name -> Completions.containsCharactersInOrder(name, query, true);
        Predicate<URI> fileMatchesQuery =
                uri -> sourcePathFiles.get(uri).declarations.stream().anyMatch(nameMatchesQuery);
        Collection<URI> open = openFiles.get();
        Stream<URI> openFirst =
                Stream.concat(
                        open.stream(),
                        sourcePathFiles.keySet().stream().filter(uri -> !open.contains(uri)));

        return openFirst
                .filter(fileMatchesQuery)
                .flatMap(this::allInFile)
                .filter(info -> nameMatchesQuery.test(info.getName()));
    }

    void updateOpenFiles() {
        finishedInitialIndex.join();

        updateIndex(openFiles.get().stream());
    }

    /** Get all declarations in an open file */
    Stream<SymbolInformation> allInFile(URI source) {
        LOG.info("Search " + source);

        JavacTask task = parseTask(source);
        Trees trees = Trees.instance(task);

        class FindDeclarations extends TreePathScanner<Void, Void> {
            List<SymbolInformation> result = new ArrayList<>();
            Optional<ClassTree> currentClass = Optional.empty();

            @Override
            public Void visitClass(ClassTree node, Void aVoid) {
                String name = node.getSimpleName().toString();
                SymbolInformation info = new SymbolInformation();

                info.setContainerName(packageName());
                info.setKind(SymbolKind.Class);
                info.setName(name);
                findTreeName(name, getCurrentPath(), trees).ifPresent(info::setLocation);

                result.add(info);

                // Push current class and continue
                Optional<ClassTree> previousClass = currentClass;

                currentClass = Optional.of(node);
                super.visitClass(node, aVoid);
                currentClass = previousClass;

                return null;
            }

            @Override
            public Void visitMethod(MethodTree node, Void aVoid) {
                boolean constructor = node.getName().contentEquals("<init>");
                String name =
                        constructor
                                ? currentClass.get().getSimpleName().toString()
                                : node.getName().toString();
                SymbolInformation info = new SymbolInformation();

                info.setContainerName(qualifiedClassName());
                info.setKind(constructor ? SymbolKind.Constructor : SymbolKind.Method);
                info.setName(name);
                findTreeName(name, getCurrentPath(), trees).ifPresent(info::setLocation);

                result.add(info);

                return super.visitMethod(node, aVoid);
            }

            @Override
            public Void visitVariable(VariableTree node, Void aVoid) {
                // If this is a field
                if (getCurrentPath().getParentPath().getLeaf().getKind() == Tree.Kind.CLASS) {
                    String name = node.getName().toString();
                    SymbolInformation info = new SymbolInformation();

                    info.setContainerName(qualifiedClassName());
                    info.setName(name);
                    info.setKind(SymbolKind.Property);
                    findTreeName(name, getCurrentPath(), trees).ifPresent(info::setLocation);

                    result.add(info);
                }

                return super.visitVariable(node, aVoid);
            }

            private String qualifiedClassName() {
                String packageName = packageName();
                String className = currentClass.get().getSimpleName().toString();

                return packageName.isEmpty() ? className : packageName + "." + className;
            }

            private String packageName() {
                return Objects.toString(getCurrentPath().getCompilationUnit().getPackageName(), "");
            }
        }

        FindDeclarations scan = new FindDeclarations();

        try {
            CompilationUnitTree tree = task.parse().iterator().next();

            scan.scan(tree, null);

            return scan.result.stream();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    boolean isTopLevelClass(String qualifiedName) {
        return doFindDeclaringFile(qualifiedName).isPresent();
    }

    Stream<ReachableClass> accessibleTopLevelClasses(String fromPackage) {
        finishedInitialIndex.join();

        return sourcePathFiles.values().stream().flatMap(doAccessibleTopLevelClasses(fromPackage));
    }

    private Function<SourceFileIndex, Stream<ReachableClass>> doAccessibleTopLevelClasses(
            String fromPackage) {
        return index ->
                index.topLevelClasses
                        .stream()
                        .filter(c -> c.publicClass || c.packageName.equals(fromPackage));
    }

    Stream<ReachableClass> allTopLevelClasses() {
        finishedInitialIndex.join();

        return sourcePathFiles.values().stream().flatMap(index -> index.topLevelClasses.stream());
    }

    Optional<URI> findDeclaringFile(TypeElement topLevelClass) {
        updateOpenFiles();

        String qualifiedName = topLevelClass.getQualifiedName().toString();

        return doFindDeclaringFile(qualifiedName);
    }

    private Optional<URI> doFindDeclaringFile(String qualifiedName) {
        String packageName = Completions.mostIds(qualifiedName),
                className = Completions.lastId(qualifiedName);
        Predicate<Map.Entry<URI, SourceFileIndex>> containsClass =
                entry -> {
                    SourceFileIndex index = entry.getValue();

                    return index.packageName.equals(packageName)
                            && index.topLevelClasses
                                    .stream()
                                    .anyMatch(c -> c.className.equals(className));
                };

        return sourcePathFiles
                .entrySet()
                .stream()
                .filter(containsClass)
                .map(entry -> entry.getKey())
                .findFirst();
    }

    /** Update a file in the index */
    private void update(CompilationUnitTree compilationUnit) {
        URI file = compilationUnit.getSourceFile().toUri();
        SourceFileIndex index = new SourceFileIndex();

        index.packageName = Objects.toString(compilationUnit.getPackageName(), "");

        new TreePathScanner<Void, Void>() {
            int classDepth = 0;

            @Override
            public Void visitClass(ClassTree node, Void aVoid) {
                // If this is a top-level class, add qualified name to special topLevelClasses index
                if (classDepth == 0) {
                    String className = node.getSimpleName().toString();
                    Set<Modifier> flags = node.getModifiers().getFlags();
                    boolean publicClass = flags.contains(Modifier.PUBLIC),
                            hasTypeParameters = !node.getTypeParameters().isEmpty();
                    boolean publicConstructor = false, packagePrivateConstructor = false;
                    boolean hasExplicitConstructors = false;

                    for (Tree each : node.getMembers()) {
                        if (each instanceof MethodTree) {
                            MethodTree method = (MethodTree) each;

                            if (method.getName().contentEquals("<init>")) {
                                hasExplicitConstructors = true;

                                Set<Modifier> methodFlags = method.getModifiers().getFlags();

                                if (publicClass && methodFlags.contains(Modifier.PUBLIC))
                                    publicConstructor = true;
                                else if (!methodFlags.contains(Modifier.PROTECTED)
                                        && !methodFlags.contains(Modifier.PRIVATE))
                                    packagePrivateConstructor = true;
                            }
                        }
                    }

                    if (!hasExplicitConstructors) {
                        publicConstructor = publicClass;
                        packagePrivateConstructor = !publicClass;
                    }

                    index.topLevelClasses.add(
                            new ReachableClass(
                                    index.packageName,
                                    className,
                                    publicClass,
                                    publicConstructor,
                                    packagePrivateConstructor,
                                    hasTypeParameters));
                }

                // Add simple name to declarations
                index.declarations.add(node.getSimpleName().toString());

                // Recurse, but remember that anything inside isn't a top-level class
                classDepth++;
                super.visitClass(node, aVoid);
                classDepth--;

                return null;
            }

            @Override
            public Void visitMethod(MethodTree node, Void aVoid) {
                index.declarations.add(node.getName().toString());

                return super.visitMethod(node, aVoid);
            }

            @Override
            public Void visitVariable(VariableTree node, Void aVoid) {
                if (getCurrentPath().getParentPath().getLeaf().getKind() == Tree.Kind.CLASS)
                    index.declarations.add(node.getName().toString());

                return super.visitVariable(node, aVoid);
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree node, Void aVoid) {
                index.references.add(node.getIdentifier().toString());

                return super.visitMemberSelect(node, aVoid);
            }

            @Override
            public Void visitMemberReference(MemberReferenceTree node, Void aVoid) {
                index.references.add(node.getName().toString());

                return super.visitMemberReference(node, aVoid);
            }

            @Override
            public Void visitIdentifier(IdentifierTree node, Void aVoid) {
                index.references.add(node.getName().toString());

                return super.visitIdentifier(node, aVoid);
            }
        }.scan(compilationUnit, null);

        sourcePathFiles.put(file, index);
    }

    Collection<URI> potentialReferences(String name) {
        updateOpenFiles();

        Map<URI, SourceFileIndex> hasName =
                Maps.filterValues(sourcePathFiles, index -> index.references.contains(name));

        return hasName.keySet();
    }

    /** Find a more accurate position for expression tree by searching for its name. */
    static Optional<Location> findTreeName(CharSequence name, TreePath path, Trees trees) {
        if (path == null) return Optional.empty();

        SourcePositions sourcePositions = trees.getSourcePositions();
        CompilationUnitTree compilationUnit = path.getCompilationUnit();
        long startExpr = sourcePositions.getStartPosition(compilationUnit, path.getLeaf());

        if (startExpr == Diagnostic.NOPOS) return Optional.empty();

        CharSequence content;
        try {
            content = compilationUnit.getSourceFile().getCharContent(false);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        int startSymbol = indexOf(content, name, (int) startExpr);

        if (startSymbol == -1) return Optional.empty();

        int line = (int) compilationUnit.getLineMap().getLineNumber(startSymbol);
        int column = (int) compilationUnit.getLineMap().getColumnNumber(startSymbol);

        return Optional.of(
                new Location(
                        compilationUnit.getSourceFile().toUri().toString(),
                        new Range(
                                new Position(line - 1, column - 1),
                                new Position(line - 1, column + name.length() - 1))));
    }

    /**
     * Adapted from java.util.String.
     *
     * <p>The source is the character array being searched, and the target is the string being
     * searched for.
     *
     * @param source the characters being searched.
     * @param target the characters being searched for.
     * @param fromIndex the index to begin searching from.
     */
    private static int indexOf(CharSequence source, CharSequence target, int fromIndex) {
        int sourceOffset = 0,
                sourceCount = source.length(),
                targetOffset = 0,
                targetCount = target.length();

        if (fromIndex >= sourceCount) {
            return (targetCount == 0 ? sourceCount : -1);
        }
        if (fromIndex < 0) {
            fromIndex = 0;
        }
        if (targetCount == 0) {
            return fromIndex;
        }

        char first = target.charAt(targetOffset);
        int max = sourceOffset + (sourceCount - targetCount);

        for (int i = sourceOffset + fromIndex; i <= max; i++) {
            /* Look for first character. */
            if (source.charAt(i) != first) {
                while (++i <= max && source.charAt(i) != first) ;
            }

            /* Found first character, now look apply the rest of v2 */
            if (i <= max) {
                int j = i + 1;
                int end = j + targetCount - 1;
                for (int k = targetOffset + 1;
                        j < end && source.charAt(j) == target.charAt(k);
                        j++, k++) ;

                if (j == end) {
                    /* Found whole string. */
                    return i - sourceOffset;
                }
            }
        }
        return -1;
    }

    private static SymbolKind symbolInformationKind(ElementKind kind) {
        switch (kind) {
            case PACKAGE:
                return SymbolKind.Package;
            case ENUM:
            case ENUM_CONSTANT:
                return SymbolKind.Enum;
            case CLASS:
                return SymbolKind.Class;
            case ANNOTATION_TYPE:
            case INTERFACE:
                return SymbolKind.Interface;
            case FIELD:
                return SymbolKind.Property;
            case PARAMETER:
            case LOCAL_VARIABLE:
            case EXCEPTION_PARAMETER:
            case TYPE_PARAMETER:
                return SymbolKind.Variable;
            case METHOD:
            case STATIC_INIT:
            case INSTANCE_INIT:
                return SymbolKind.Method;
            case CONSTRUCTOR:
                return SymbolKind.Constructor;
            case OTHER:
            case RESOURCE_VARIABLE:
            default:
                return SymbolKind.String;
        }
    }

    private CompilationUnitTree parse(URI source) {
        try {
            return parseTask(source).parse().iterator().next();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private JavacTask parseTask(URI source) {
        Optional<String> content = activeContent.apply(source);
        JavaFileObject file =
                content.map(text -> (JavaFileObject) new StringFileObject(text, source))
                        .orElseGet(() -> emptyFileManager.getRegularFile(new File(source)));

        return parser.getTask(
                null,
                emptyFileManager,
                err -> LOG.warning(err.getMessage(Locale.getDefault())),
                Collections.emptyList(),
                null,
                Collections.singletonList(file));
    }

    private static final Logger LOG = Logger.getLogger("main");
}
