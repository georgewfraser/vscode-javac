package org.javacs_server;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.tree.*;
import com.sun.source.util.DocTrees;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.tools.*;

public class Docs {

    /** File manager with source-path + platform sources, which we will use to look up individual source files */
    private final StandardJavaFileManager fileManager;

    private static Path srcZip() {
        try {
            var fs = FileSystems.newFileSystem(Lib.SRC_ZIP, Docs.class.getClassLoader());
            return fs.getPath("/");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    Docs(Set<Path> sourcePath) {
        this.fileManager =
                ServiceLoader.load(JavaCompiler.class).iterator().next().getStandardFileManager(__ -> {}, null, null);

        // Compute the full source path, including src.zip for platform classes
        var sourcePathFiles = sourcePath.stream().map(Path::toFile).collect(Collectors.toSet());

        try {
            fileManager.setLocation(StandardLocation.SOURCE_PATH, sourcePathFiles);
            fileManager.setLocationFromPaths(StandardLocation.MODULE_SOURCE_PATH, Set.of(srcZip()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Look up the javadoc associated with `method` */
    public Optional<DocCommentTree> methodDoc(ExecutableElement method) {
        var classElement = (TypeElement) method.getEnclosingElement();
        var className = classElement.getQualifiedName().toString();
        var methodName = method.getSimpleName().toString();
        return memberDoc(className, methodName);
    }

    /** Find and root the source code associated with `method` */
    public Optional<MethodTree> methodTree(ExecutableElement method) {
        var classElement = (TypeElement) method.getEnclosingElement();
        var className = classElement.getQualifiedName().toString();
        var methodName = method.getSimpleName().toString();
        var parameterTypes =
                method.getParameters().stream().map(p -> p.asType().toString()).collect(Collectors.toList());
        return findMethod(className, methodName, parameterTypes);
    }

    /** Look up the javadoc associated with `type` */
    public Optional<DocCommentTree> classDoc(TypeElement type) {
        return classDoc(type.getQualifiedName().toString());
    }

    public Optional<DocCommentTree> classDoc(String qualifiedName) {
        Objects.requireNonNull(qualifiedName);

        return findDoc(qualifiedName, null);
    }

    private Optional<JavaFileObject> file(String className) {
        try {
            var fromSourcePath =
                    fileManager.getJavaFileForInput(
                            StandardLocation.SOURCE_PATH, className, JavaFileObject.Kind.SOURCE);
            if (fromSourcePath != null) return Optional.of(fromSourcePath);
            for (var module : Classes.JDK_MODULES) {
                var moduleLocation = fileManager.getLocationForModule(StandardLocation.MODULE_SOURCE_PATH, module);
                if (moduleLocation == null) continue;
                var fromModuleSourcePath =
                        fileManager.getJavaFileForInput(moduleLocation, className, JavaFileObject.Kind.SOURCE);
                if (fromModuleSourcePath != null) return Optional.of(fromModuleSourcePath);
            }
            return Optional.empty();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean memberNameEquals(Tree member, String name) {
        if (member instanceof VariableTree) {
            var variable = (VariableTree) member;
            return variable.getName().contentEquals(name);
        } else if (member instanceof MethodTree) {
            var method = (MethodTree) member;
            return method.getName().contentEquals(name);
        } else return false;
    }

    private Optional<DocCommentTree> findDoc(String className, String memberName) {
        var file = file(className);
        if (!file.isPresent()) return Optional.empty();
        var task = Parser.parseTask(file.get());
        CompilationUnitTree root;
        try {
            var it = task.parse().iterator();
            if (!it.hasNext()) {
                LOG.warning("Found no CompilationUnitTree in " + file);
                return Optional.empty();
            }
            root = it.next();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        var docs = DocTrees.instance(task);
        var trees = Trees.instance(task);
        class Find extends TreeScanner<Void, Void> {
            Optional<DocCommentTree> result = Optional.empty();

            @Override
            public Void visitClass(ClassTree node, Void aVoid) {
                // TODO this will be wrong when inner class has same name as top-level class
                if (node.getSimpleName().contentEquals(Parser.lastName(className))) {
                    if (memberName == null) {
                        var path = trees.getPath(root, node);
                        result = Optional.ofNullable(docs.getDocCommentTree(path));
                    } else {
                        for (var member : node.getMembers()) {
                            if (memberNameEquals(member, memberName)) {
                                var path = trees.getPath(root, member);
                                result = Optional.ofNullable(docs.getDocCommentTree(path));
                            }
                        }
                    }
                }
                return null;
            }
        }
        var find = new Find();
        find.scan(root, null);
        return find.result;
    }

    Optional<DocCommentTree> memberDoc(String className, String memberName) {
        Objects.requireNonNull(className);
        Objects.requireNonNull(memberName);

        return findDoc(className, memberName);
    }

    private boolean sameMethod(MethodTree candidate, String methodName, List<String> parameterTypes) {
        if (!candidate.getName().contentEquals(methodName)) return false;
        var params = candidate.getParameters();
        if (params.size() != parameterTypes.size()) return false;
        for (int i = 0; i < params.size(); i++) {
            var expect = parameterTypes.get(i);
            var expectSimple = Parser.lastName(expect);
            var p = params.get(i);
            var t = p.getType();
            if (!(t instanceof IdentifierTree)) {
                LOG.warning(
                        "Parameter " + p.getName() + " of method " + candidate.getName() + " is not an IdentifierTree");
                return false;
            }
            var id = (IdentifierTree) t;
            var simple = Parser.lastName(id.getName().toString());

            if (!simple.equals(expectSimple)) return false;
        }
        return true;
    }

    private Optional<MethodTree> findMethod(String className, String methodName, List<String> parameterTypes) {
        Objects.requireNonNull(className);
        Objects.requireNonNull(methodName);

        var file = file(className);
        if (!file.isPresent()) return Optional.empty();
        var task = Parser.parseTask(file.get());
        CompilationUnitTree root;
        try {
            root = task.parse().iterator().next();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        class Find extends TreeScanner<Void, Void> {
            Optional<MethodTree> result = Optional.empty();

            @Override
            public Void visitClass(ClassTree node, Void aVoid) {
                // TODO this will be wrong when inner class has same name as top-level class
                if (node.getSimpleName().contentEquals(Parser.lastName(className))) {
                    for (var member : node.getMembers()) {
                        if (member instanceof MethodTree) {
                            var method = (MethodTree) member;
                            if (sameMethod(method, methodName, parameterTypes)) result = Optional.of(method);
                        }
                    }
                }
                return null;
            }
        }
        var find = new Find();
        find.scan(root, null);
        return find.result;
    }

    private static final Pattern HTML_TAG = Pattern.compile("<(\\w+)>");

    private static boolean isHtml(String text) {
        var tags = HTML_TAG.matcher(text);
        while (tags.find()) {
            var tag = tags.group(1);
            var close = String.format("</%s>", tag);
            var findClose = text.indexOf(close, tags.end());
            if (findClose != -1) return true;
        }
        return false;
    }

    /** If `commentText` looks like HTML, convert it to markdown */
    static String htmlToMarkdown(String commentText) {
        if (isHtml(commentText)) {
            return TipFormatter.asMarkdown(commentText);
        } else return commentText;
    }

    private static final Logger LOG = Logger.getLogger("main");
}
