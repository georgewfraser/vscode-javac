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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.tools.javac.api.JavacTool;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import javax.lang.model.element.TypeElement;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;
import javax.tools.StandardLocation;
import org.javacs.pubapi.PubApi;
import org.javacs.pubapi.PubapiVisitor;

/**
 * An implementation of JavaFileManager that removes any .java source files where there is an
 * up-to-date .class file
 */
class IncrementalFileManager extends ForwardingJavaFileManager<JavaFileManager> {
    private final Set<URI> warnedHidden = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final JavacTool javac = JavacTool.create();
    private final JavaFileManager classOnlyFileManager;

    IncrementalFileManager(JavaFileManager delegate) {
        super(delegate);

        classOnlyFileManager =
                new ForwardingJavaFileManager<JavaFileManager>(delegate) {
                    @Override
                    public Iterable<JavaFileObject> list(
                            Location location, String packageName, Set<Kind> kinds, boolean recurse)
                            throws IOException {
                        if (location == StandardLocation.SOURCE_PATH)
                            kinds = Sets.filter(kinds, k -> k != Kind.SOURCE);

                        return super.list(location, packageName, kinds, recurse);
                    }

                    @Override
                    public JavaFileObject getJavaFileForInput(
                            Location location, String className, JavaFileObject.Kind kind)
                            throws IOException {
                        if (kind == Kind.SOURCE) return null;
                        else return super.getJavaFileForInput(location, className, kind);
                    }

                    @Override
                    public FileObject getFileForInput(
                            Location location, String packageName, String relativeName)
                            throws IOException {
                        if (location == StandardLocation.SOURCE_PATH) return null;
                        else return super.getFileForInput(location, packageName, relativeName);
                    }
                };
    }

    @Override
    public Iterable<JavaFileObject> list(
            Location location, String packageName, Set<Kind> kinds, boolean recurse)
            throws IOException {
        Iterable<JavaFileObject> list = super.list(location, packageName, kinds, recurse);

        if (location == StandardLocation.SOURCE_PATH)
            return Iterables.filter(list, source -> !hasUpToDateClassFiles(packageName, source));
        else return list;
    }

    @Override
    public JavaFileObject getJavaFileForInput(
            Location location, String className, JavaFileObject.Kind kind) throws IOException {
        if (location == StandardLocation.SOURCE_PATH && hasUpToDateClassFile(className))
            return null;
        else return super.getJavaFileForInput(location, className, kind);
    }

    @Override
    public FileObject getFileForInput(Location location, String packageName, String relativeName)
            throws IOException {
        String className = packageName.isEmpty() ? relativeName : packageName + "." + relativeName;

        if (location == StandardLocation.SOURCE_PATH && hasUpToDateClassFile(className))
            return null;
        else return super.getFileForInput(location, packageName, relativeName);
    }

    private boolean hasUpToDateClassFiles(String packageName, JavaFileObject sourceFile) {
        Optional<JavaFileObject> outputFile = primaryClassFile(packageName, sourceFile);
        boolean hidden =
                outputFile.isPresent()
                                && outputFile.get().getLastModified()
                                        >= sourceFile.getLastModified()
                        || hasUpToDateSignatures(packageName, sourceFile);

        if (hidden && !warnedHidden.contains(sourceFile.toUri())) {
            LOG.warning("Hiding " + sourceFile.toUri() + " in favor of " + outputFile.orElse(null));

            warnedHidden.add(sourceFile.toUri());
        }

        return hidden;
    }

    /**
     * Cache of whether a particular source file had up-to-date class files at a particular time.
     *
     * <p>We're going to assume that if there were up-to-date class files, and the lastModified time
     * of the source file has not changed, there are still up-to-date class files.
     */
    private Map<CheckedSignature, Boolean> upToDate = new HashMap<>();

    private static class CheckedSignature {
        final URI sourceUri;
        final Instant lastModified;

        public CheckedSignature(URI sourceUri, Instant lastModified) {
            this.sourceUri = sourceUri;
            this.lastModified = lastModified;
        }

        @Override
        public boolean equals(Object maybe) {
            if (maybe instanceof CheckedSignature) {
                CheckedSignature that = (CheckedSignature) maybe;

                return this.sourceUri.equals(that.sourceUri)
                        && this.lastModified.equals(that.lastModified);
            } else return false;
        }

        @Override
        public int hashCode() {
            return Objects.hash(sourceUri, lastModified);
        }
    }

    private boolean hasUpToDateSignatures(String packageName, JavaFileObject sourceFile) {
        CheckedSignature key =
                new CheckedSignature(
                        sourceFile.toUri(), Instant.ofEpochMilli(sourceFile.getLastModified()));

        return upToDate.computeIfAbsent(key, __ -> computeUpToDate(packageName, sourceFile));
    }

    private boolean computeUpToDate(String packageName, JavaFileObject sourceFile) {
        try {
            JavacTask task =
                    javac.getTask(
                            null,
                            classOnlyFileManager,
                            __ -> {},
                            ImmutableList.of(),
                            null,
                            ImmutableList.of(sourceFile));
            CompilationUnitTree tree = task.parse().iterator().next();

            for (Tree each : tree.getTypeDecls()) {
                if (each instanceof ClassTree) {
                    ClassTree sourceClass = (ClassTree) each;
                    String qualifiedName =
                            packageName.isEmpty()
                                    ? sourceClass.getSimpleName().toString()
                                    : packageName + "." + sourceClass.getSimpleName();

                    if (!hasUpToDateSignature(qualifiedName)) {
                        JavaFileObject classFile =
                                super.getJavaFileForInput(
                                        StandardLocation.CLASS_PATH,
                                        qualifiedName,
                                        JavaFileObject.Kind.CLASS);

                        if (classFile != null) {
                            LOG.warning(
                                    String.format(
                                            "%s has a different signature than %s",
                                            sourceFile.toUri(), classFile));
                        }

                        return false;
                    }
                }
            }

            LOG.info(
                    String.format(
                            "%s appears to be out-of-date but its class files have a matching public API",
                            sourceFile.toUri()));

            return true;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Optional<JavaFileObject> primaryClassFile(
            String packageName, JavaFileObject sourceFile) {
        String primaryClassName = primaryClassSimpleName(sourceFile);
        String qualifiedName =
                packageName.isEmpty() ? primaryClassName : packageName + "." + primaryClassName;

        try {
            return Optional.ofNullable(
                    super.getJavaFileForInput(
                            StandardLocation.CLASS_PATH, qualifiedName, JavaFileObject.Kind.CLASS));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String primaryClassSimpleName(JavaFileObject sourceFile) {
        String[] filePath = sourceFile.toUri().getPath().split("/");

        assert filePath.length > 0 : sourceFile + " has not path";

        String fileName = filePath[filePath.length - 1];

        assert fileName.endsWith(".java") : sourceFile + " does not end with .java";

        return fileName.substring(0, fileName.length() - ".java".length());
    }

    private boolean hasUpToDateClassFile(String qualifiedName) {
        try {
            JavaFileObject
                    sourceFile =
                            super.getJavaFileForInput(
                                    StandardLocation.SOURCE_PATH,
                                    qualifiedName,
                                    JavaFileObject.Kind.SOURCE),
                    outputFile =
                            super.getJavaFileForInput(
                                    StandardLocation.CLASS_PATH,
                                    qualifiedName,
                                    JavaFileObject.Kind.CLASS);
            long sourceModified = sourceFile == null ? 0 : sourceFile.getLastModified(),
                    outputModified = outputFile == null ? 0 : outputFile.getLastModified();
            boolean hidden =
                    outputModified >= sourceModified || hasUpToDateSignature(qualifiedName);

            // TODO remove
            // if (!hidden) {
            //     LOG.warning("Source and class signatures do not match...");
            //     LOG.warning("\t" + sourceSignature(qualifiedName));
            //     LOG.warning("\t" + classSignature(qualifiedName));
            // }

            if (hidden
                    && sourceFile != null
                    && outputFile != null
                    && !warnedHidden.contains(sourceFile.toUri())) {
                LOG.warning("Hiding " + sourceFile.toUri() + " in favor of " + outputFile.toUri());

                warnedHidden.add(sourceFile.toUri());
            }

            if (!hidden && warnedHidden.contains(sourceFile.toUri()))
                warnedHidden.remove(sourceFile.toUri());

            return hidden;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean hasUpToDateSignature(String qualifiedName) {
        return sourceSignature(qualifiedName).equals(classSignature(qualifiedName));
    }

    /** Signatures of all non-private methods and fields in a .java file */
    Optional<PubApi> sourceSignature(String qualifiedName) {
        JavacTask task =
                javac.getTask(
                        null, fileManager, __ -> {}, ImmutableList.of(), null, ImmutableList.of());
        TypeElement element = task.getElements().getTypeElement(qualifiedName);

        return signature(element);
    }

    /** Signatures of all non-private methods and fields in a .class file */
    Optional<PubApi> classSignature(String qualifiedName) {
        JavacTask task =
                javac.getTask(
                        null,
                        classOnlyFileManager,
                        __ -> {},
                        ImmutableList.of(),
                        null,
                        ImmutableList.of());
        TypeElement element = task.getElements().getTypeElement(qualifiedName);

        return signature(element);
    }

    private static Optional<PubApi> signature(TypeElement element) {
        if (element == null) return Optional.empty();
        else {
            PubapiVisitor visit = new PubapiVisitor();

            visit.scan(element);

            PubApi api = visit.getCollectedPubApi();

            return Optional.of(api);
        }
    }

    private static final Logger LOG = Logger.getLogger("main");
}
