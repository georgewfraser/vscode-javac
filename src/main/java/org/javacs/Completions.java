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

import com.google.common.reflect.ClassPath;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Scope;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Symbol;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Elements;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.TextEdit;

class Completions {

    static Stream<CompletionItem> at(FocusedResult compiled, SymbolIndex index, Javadocs docs) {
        Function<TreePath, Completions> newCompletions =
                path -> new Completions(compiled.task, compiled.classPath, index, docs, path);
        return compiled.cursor.map(newCompletions).map(Completions::get).orElseGet(Stream::empty);
    }

    private final JavacTask task;
    private final ClassPathIndex classPath;
    private final SymbolIndex sourcePath;
    private final Javadocs docs;
    private final Trees trees;
    private final Elements elements;
    private final Name thisName, superName;
    private final CompilationUnitTree compilationUnit;
    private final TreePath path;
    private final CursorContext context;

    private Completions(
            JavacTask task,
            ClassPathIndex classPath,
            SymbolIndex sourcePath,
            Javadocs docs,
            TreePath path) {
        this.task = task;
        this.trees = Trees.instance(task);
        this.elements = task.getElements();
        this.thisName = task.getElements().getName("this");
        this.superName = task.getElements().getName("super");
        this.classPath = classPath;
        this.sourcePath = sourcePath;
        this.docs = docs;
        this.compilationUnit = path.getCompilationUnit();
        this.path = path;
        this.context = CursorContext.from(path);
    }

    private Stream<CompletionItem> get() {
        Tree leaf = path.getLeaf();
        Scope scope = trees.getScope(path);

        if (leaf instanceof MemberSelectTree) {
            MemberSelectTree select = (MemberSelectTree) leaf;
            TreePath expressionPath = new TreePath(path.getParentPath(), select.getExpression());

            return completeMembers(
                    expressionPath, partialIdentifier(select.getIdentifier()), scope);
        } else if (leaf instanceof MemberReferenceTree) {
            MemberReferenceTree select = (MemberReferenceTree) leaf;
            TreePath expressionPath =
                    new TreePath(path.getParentPath(), select.getQualifierExpression());

            return completeMembers(expressionPath, partialIdentifier(select.getName()), scope);
        } else if (leaf instanceof IdentifierTree) {
            IdentifierTree id = (IdentifierTree) leaf;

            return completeIdentifier(partialIdentifier(id.getName()), scope);
        } else return Stream.empty();
    }

    private String partialIdentifier(Name name) {
        if (name.contentEquals("<error>")) return "";
        else return name.toString();
    }

    /** Suggest all available identifiers */
    private Stream<CompletionItem> completeIdentifier(String partialIdentifier, Scope from) {
        switch (context) {
            case Import:
                return packageMembers("", partialIdentifier).flatMap(this::completionItem);
            case NewClass:
                {
                    Predicate<ExecutableElement> accessible =
                            init ->
                                    trees.isAccessible(
                                            from,
                                            init,
                                            (DeclaredType) init.getEnclosingElement().asType());
                    Stream<CompletionItem> alreadyImported =
                            alreadyImportedCompletions(partialIdentifier, from)
                                    .flatMap(this::explodeConstructors)
                                    .filter(accessible)
                                    .flatMap(this::completionItem);
                    Stream<CompletionItem> notYetImported =
                            notImportedConstructors(partialIdentifier, from);

                    return Stream.concat(alreadyImported, notYetImported);
                }
            default:
                {
                    Predicate<Element> accessible =
                            e -> {
                                if (e == null) return false;
                                // Class names
                                else if (e instanceof TypeElement)
                                    return trees.isAccessible(from, (TypeElement) e);
                                else if (e.getEnclosingElement() == null) return false;
                                // Members of other classes
                                else if (e.getEnclosingElement() instanceof DeclaredType)
                                    return trees.isAccessible(
                                            from, e, (DeclaredType) e.getEnclosingElement());
                                // Local variables
                                else return true;
                            };
                    Stream<CompletionItem> alreadyImported =
                            alreadyImportedCompletions(partialIdentifier, from)
                                    .filter(accessible)
                                    .flatMap(this::completionItem);
                    Stream<CompletionItem> notYetImported =
                            notImportedClasses(partialIdentifier, from);

                    return Stream.concat(alreadyImported, notYetImported);
                }
        }
    }

    /** Suggest all accessible members of expression */
    private Stream<CompletionItem> completeMembers(
            TreePath expression, String partialIdentifier, Scope from) {
        switch (context) {
            case NewClass:
                {
                    Predicate<ExecutableElement> isAccessible =
                            init ->
                                    trees.isAccessible(
                                            from,
                                            init,
                                            (DeclaredType) init.getEnclosingElement().asType());
                    return allMembers(expression, partialIdentifier, from, false)
                            .flatMap(this::explodeConstructors)
                            .filter(isAccessible)
                            .flatMap(this::completionItem);
                }
            case Import:
                {
                    Predicate<Element> isAccessible =
                            member ->
                                    !(member instanceof TypeElement)
                                            || trees.isAccessible(from, (TypeElement) member);
                    return allMembers(expression, partialIdentifier, from, false)
                            .filter(isAccessible)
                            .flatMap(this::completionItem);
                }
            default:
                {
                    DeclaredType type =
                            (DeclaredType) task.getTypes().erasure(trees.getTypeMirror(expression));

                    return allMembers(
                                    expression,
                                    partialIdentifier,
                                    from,
                                    context == CursorContext.Reference)
                            .filter(member -> member.getKind() != ElementKind.CONSTRUCTOR)
                            .filter(e -> trees.isAccessible(from, e, type))
                            .flatMap(this::completionItem);
                }
        }
    }

    private Stream<? extends Element> allMembers(
            TreePath expression, String partialIdentifier, Scope from, boolean isMethodReference) {
        Element element = trees.getElement(expression);

        if (element == null) return Stream.empty();

        // com.foo.?
        if (element instanceof PackageElement) {
            PackageElement packageElement = (PackageElement) element;

            return packageMembers(packageElement.getQualifiedName().toString(), partialIdentifier);
        }
        // MyClass.?
        else if (element instanceof TypeElement) {
            // OuterClass.this, OuterClass.super
            Stream<? extends Element> thisAndSuper =
                    thisScopes(from)
                            .stream()
                            .filter(scope -> scope.getEnclosingClass().equals(element))
                            .flatMap(this::thisAndSuper);
            // MyClass.?
            Predicate<Element> isAccessible =
                    e -> {
                        return (isMethodReference && e.getKind() == ElementKind.METHOD)
                                || e.getModifiers().contains(Modifier.STATIC);
                    };
            Stream<? extends Element> members =
                    elements.getAllMembers((TypeElement) element).stream().filter(isAccessible);
            // MyClass.class
            Element dotClass =
                    new Symbol.VarSymbol(
                            Flags.PUBLIC | Flags.STATIC | Flags.FINAL,
                            (com.sun.tools.javac.util.Name) task.getElements().getName("class"),
                            (com.sun.tools.javac.code.Type) element.asType(),
                            (Symbol) element);

            Predicate<Element> matchesPartialIdentifier =
                    e -> containsCharactersInOrder(e.getSimpleName(), partialIdentifier, false);

            return Stream.concat(Stream.of(dotClass), Stream.concat(thisAndSuper, members))
                    .filter(matchesPartialIdentifier);
        }
        // myValue.?
        else {
            DeclaredType type =
                    (DeclaredType) task.getTypes().erasure(trees.getTypeMirror(expression));
            List<? extends Element> members =
                    elements.getAllMembers((TypeElement) type.asElement());

            return members.stream()
                    .filter(e -> !e.getModifiers().contains(Modifier.STATIC))
                    .filter(
                            e ->
                                    containsCharactersInOrder(
                                            e.getSimpleName(), partialIdentifier, false));
        }
    }

    private Stream<? extends Element> packageMembers(
            String parentPackage, String partialIdentifier) {
        // Source-path packages that match parentPackage.partialIdentifier
        Stream<PackageElement> packages = subPackages(parentPackage, partialIdentifier);
        Stream<TypeElement> sourcePathClasses =
                sourcePathClassesInPackage(parentPackage, partialIdentifier);
        Stream<TypeElement> classPathClasses =
                classPath
                        .topLevelClassesIn(parentPackage, partialIdentifier)
                        .flatMap(this::loadFromClassPath);

        return Stream.concat(packages, Stream.concat(sourcePathClasses, classPathClasses));
    }

    private Stream<TypeElement> loadFromClassPath(ClassPath.ClassInfo info) {
        TypeElement found = elements.getTypeElement(info.getName());

        if (found == null) return Stream.empty();
        else return Stream.of(found);
    }

    /** All sub-packages of parentPackage that match partialIdentifier */
    private Stream<PackageElement> subPackages(String parentPackage, String partialIdentifier) {
        String prefix = parentPackage.isEmpty() ? "" : parentPackage + ".";
        Stream<String> sourcePathMembers =
                sourcePath
                        .allTopLevelClasses()
                        .map(c -> c.packageName)
                        .filter(packageName -> packageName.startsWith(prefix));
        Stream<String> classPathMembers = classPath.packagesStartingWith(prefix);
        Set<String> next =
                Stream.concat(sourcePathMembers, classPathMembers)
                        .map(p -> p.substring(prefix.length()))
                        .map(Completions::firstId)
                        .filter(p -> containsCharactersInOrder(p, partialIdentifier, false))
                        .collect(Collectors.toSet());

        // Load 1 member of package to force javac to recognize that it exists
        for (String part : next) {
            classPath.loadPackage(prefix + part).ifPresent(this::tryLoad);
        }

        return next.stream()
                .map(last -> elements.getPackageElement(prefix + last))
                .filter(Objects::nonNull);
    }

    private boolean isAlreadyImported(String qualifiedName) {
        String packageName = mostIds(qualifiedName);

        if (packageName.equals("java.lang")) return true;

        if (packageName.equals(Objects.toString(compilationUnit.getPackageName(), ""))) return true;

        for (ImportTree each : compilationUnit.getImports()) {
            if (each.isStatic()) continue;

            String importName = importId(each);

            if (isStarImport(each) && mostIds(importName).equals(packageName)) return true;
            else if (importName.equals(qualifiedName)) return true;
        }

        return false;
    }

    private static boolean isStarImport(ImportTree tree) {
        String importName = importId(tree);

        return importName.endsWith(".*");
    }

    private static String importId(ImportTree tree) {
        return tree.getQualifiedIdentifier().toString();
    }

    static String firstId(String qualifiedName) {
        int firstDot = qualifiedName.indexOf('.');

        if (firstDot == -1) return qualifiedName;
        else return qualifiedName.substring(0, firstDot);
    }

    static String mostIds(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');

        if (lastDot == -1) return "";
        else return qualifiedName.substring(0, lastDot);
    }

    static String lastId(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');

        if (lastDot == -1) return qualifiedName;
        else return qualifiedName.substring(lastDot + 1);
    }

    private Stream<ExecutableElement> explodeConstructors(Element element) {
        if (element.getKind() != ElementKind.CLASS) return Stream.empty();

        try {
            return elements.getAllMembers((TypeElement) element)
                    .stream()
                    .filter(e -> e.getKind() == ElementKind.CONSTRUCTOR)
                    .map(e -> (ExecutableElement) e);
        } catch (Symbol.CompletionFailure failed) {
            LOG.warning(failed.getMessage());

            return Stream.empty();
        }
    }

    /** Suggest constructors that haven't yet been imported, but are on the source or class path */
    private Stream<CompletionItem> notImportedConstructors(String partialIdentifier, Scope scope) {
        String packageName = packageName(scope);
        Stream<CompletionItem> fromSourcePath =
                accessibleSourcePathClasses(partialIdentifier, scope)
                        .filter(c -> c.hasAccessibleConstructor(packageName))
                        .map(this::completeConstructorFromSourcePath);
        Stream<CompletionItem> fromClassPath =
                accessibleClassPathClasses(partialIdentifier, scope)
                        .filter(c -> classPath.hasAccessibleConstructor(c, packageName))
                        .map(c -> completeConstructorFromClassPath(c.load()));
        // TODO remove class path classes that are also available from source path

        return Stream.concat(fromSourcePath, fromClassPath);
    }

    private CompletionItem completeConstructorFromSourcePath(ReachableClass c) {
        return completeConstructor(c.packageName, c.className, c.hasTypeParameters);
    }

    private CompletionItem completeConstructorFromClassPath(Class<?> c) {
        return completeConstructor(
                c.getPackage().getName(), c.getSimpleName(), c.getTypeParameters().length > 0);
    }

    /** Suggest classes that haven't yet been imported, but are on the source or class path */
    private Stream<CompletionItem> notImportedClasses(String partialIdentifier, Scope scope) {
        Stream<String> fromSourcePath =
                accessibleSourcePathClasses(partialIdentifier, scope).map(c -> c.qualifiedName());
        Stream<String> fromClassPath =
                accessibleClassPathClasses(partialIdentifier, scope)
                        .map(c -> c.getName())
                        .filter(name -> !sourcePath.isTopLevelClass(name));

        return Stream.concat(fromSourcePath, fromClassPath)
                .map(this::completeClassNameFromClassPath);
    }

    private Stream<ClassPath.ClassInfo> accessibleClassPathClasses(
            String partialIdentifier, Scope scope) {
        String packageName = packageName(scope);

        return classPath
                .topLevelClasses()
                .filter(c -> containsCharactersInOrder(c.getSimpleName(), partialIdentifier, false))
                .filter(c -> classPath.isAccessibleFromPackage(c, packageName));
    }

    private Stream<ReachableClass> accessibleSourcePathClasses(
            String partialIdentifier, Scope scope) {
        String packageName = packageName(scope);

        return sourcePath
                .accessibleTopLevelClasses(packageName)
                .filter(c -> containsCharactersInOrder(c.className, partialIdentifier, false));
    }

    private String packageName(Scope scope) {
        PackageElement packageEl = elements.getPackageOf(scope.getEnclosingClass());

        return packageEl == null ? "" : packageEl.getQualifiedName().toString();
    }

    /** Suggest all completions that are visible from scope */
    private Stream<? extends Element> alreadyImportedCompletions(
            String partialIdentifier, Scope scope) {
        Predicate<Element> matchesName =
                e -> containsCharactersInOrder(e.getSimpleName(), partialIdentifier, false);
        return alreadyImportedSymbols(scope).filter(matchesName);
    }

    private Stream<TypeElement> tryLoad(ClassPath.ClassInfo c) {
        try {
            TypeElement type = elements.getTypeElement(c.getName());

            if (type != null) return Stream.of(type);
            else return Stream.empty();
        } catch (Symbol.CompletionFailure failed) {
            LOG.warning(failed.getMessage());

            return Stream.empty();
        }
    }

    private Stream<? extends Element> alreadyImportedSymbols(Scope scope) {
        Collection<TypeElement> thisScopes = scopeClasses(thisScopes(scope));
        Collection<TypeElement> classScopes = classScopes(scope);
        List<Scope> methodScopes = methodScopes(scope);
        Stream<? extends Element> staticImports =
                compilationUnit.getImports().stream().flatMap(this::staticImports);
        Stream<? extends Element> elements = Stream.empty();

        if (!isStaticMethod(scope)) elements = Stream.concat(elements, thisAndSuper(scope));

        elements = Stream.concat(elements, methodScopes.stream().flatMap(this::locals));
        elements = Stream.concat(elements, thisScopes.stream().flatMap(this::instanceMembers));
        elements = Stream.concat(elements, classScopes.stream().flatMap(this::staticMembers));
        elements = Stream.concat(elements, staticImports);

        return elements;
    }

    private Stream<TypeElement> sourcePathClassesInPackage(
            String packageName, String partialClass) {
        return sourcePath
                .allTopLevelClasses()
                .filter(
                        c ->
                                c.packageName.equals(packageName)
                                        && containsCharactersInOrder(
                                                c.className, partialClass, false))
                .map(ReachableClass::qualifiedName)
                .flatMap(this::loadFromSourcePath);
    }

    private Stream<TypeElement> loadFromSourcePath(String name) {
        TypeElement found = elements.getTypeElement(name);

        if (found == null) return Stream.empty();
        else return Stream.of(found);
    }

    private Stream<? extends Element> staticImports(ImportTree tree) {
        if (!tree.isStatic()) return Stream.empty();

        if (isStarImport(tree)) {
            String parentName = mostIds(importId(tree));
            TypeElement parentElement = elements.getTypeElement(parentName);

            if (parentElement == null) {
                LOG.warning("Can't find " + parentName);

                return Stream.empty();
            }

            return parentElement.getEnclosedElements().stream();
        } else {
            String name = importId(tree);
            String className = mostIds(name);
            String memberName = lastId(name);
            TypeElement classElement = elements.getTypeElement(className);

            if (classElement == null) {
                LOG.warning("Can't find " + className);

                return Stream.empty();
            }

            for (Element each : classElement.getEnclosedElements()) {
                if (each.getSimpleName().contentEquals(memberName)) return Stream.of(each);
            }

            LOG.warning("Couldn't find " + memberName + " in " + className);

            return Stream.empty();
        }
    }

    private List<Scope> thisScopes(Scope scope) {
        List<Scope> acc = new ArrayList<>();

        while (scope != null && scope.getEnclosingClass() != null) {
            TypeElement each = scope.getEnclosingClass();
            boolean staticMethod = isStaticMethod(scope);
            boolean staticClass = each.getModifiers().contains(Modifier.STATIC);
            boolean anonymousClass = isAnonymousClass(each);

            // If this scope is a static method, terminate
            if (staticMethod) break;
            // If the user has indicated this is a static class, it's the last scope in the chain
            else if (staticClass && !anonymousClass) {
                acc.add(scope);

                break;
            }
            // If this is an inner class, add it to the chain and keep going
            else {
                acc.add(scope);

                scope = scope.getEnclosingScope();
            }
        }

        return acc;
    }

    private Collection<TypeElement> scopeClasses(Collection<Scope> scopes) {
        Map<Name, TypeElement> acc = new LinkedHashMap<>();

        for (Scope scope : scopes) {
            TypeElement each = scope.getEnclosingClass();

            acc.put(each.getQualifiedName(), each);
        }

        return acc.values();
    }

    private List<Scope> methodScopes(Scope scope) {
        List<Scope> acc = new ArrayList<>();

        while (scope != null && scope.getEnclosingClass() != null) {
            if (scope.getEnclosingMethod() != null) acc.add(scope);

            scope = scope.getEnclosingScope();
        }

        return acc;
    }

    private Collection<TypeElement> classScopes(Scope scope) {
        Map<Name, TypeElement> acc = new LinkedHashMap<>();

        while (scope != null && scope.getEnclosingClass() != null) {
            TypeElement each = scope.getEnclosingClass();

            acc.putIfAbsent(each.getQualifiedName(), each);

            scope = scope.getEnclosingScope();
        }

        return acc.values();
    }

    private Stream<? extends Element> instanceMembers(TypeElement enclosingClass) {
        return elements.getAllMembers(enclosingClass)
                .stream()
                .filter(each -> !each.getModifiers().contains(Modifier.STATIC));
    }

    private Stream<? extends Element> staticMembers(TypeElement enclosingClass) {
        return elements.getAllMembers(enclosingClass)
                .stream()
                .filter(each -> each.getModifiers().contains(Modifier.STATIC));
    }

    private Stream<? extends Element> locals(Scope scope) {
        return StreamSupport.stream(scope.getLocalElements().spliterator(), false)
                .filter(e -> !isThisOrSuper(e));
    }

    private Stream<? extends Element> thisAndSuper(Scope scope) {
        return StreamSupport.stream(scope.getLocalElements().spliterator(), false)
                .filter(e -> isThisOrSuper(e));
    }

    private boolean isStaticMethod(Scope scope) {
        return scope.getEnclosingMethod() != null
                && scope.getEnclosingMethod().getModifiers().contains(Modifier.STATIC);
    }

    private boolean isAnonymousClass(Element candidate) {
        return candidate != null
                && candidate instanceof TypeElement
                && ((TypeElement) candidate).getNestingKind() == NestingKind.ANONYMOUS;
    }

    private boolean isThisOrSuper(Element each) {
        Name name = each.getSimpleName();

        return name.equals(thisName) || name.equals(superName);
    }

    private static final Command TRIGGER_SIGNATURE_HELP =
            new Command("", "editor.action.triggerParameterHints");

    /**
     * Complete constructor with minimal type information.
     *
     * <p>This is important when we're autocompleting new ? with a class that we haven't yet
     * imported. We don't yet have detailed type information or javadocs, and it's expensive to
     * retrieve them. So we autocomplete a minimal constructor, and let signature-help fill in the
     * details.
     */
    private CompletionItem completeConstructor(
            String packageName, String className, boolean hasTypeParameters) {
        CompletionItem item = new CompletionItem();
        String qualifiedName = packageName.isEmpty() ? className : packageName + "." + className;
        String key = String.format("%s#<init>", className);
        String insertText = className;

        if (hasTypeParameters) insertText += "<>";

        insertText += "($0)";

        item.setKind(CompletionItemKind.Constructor);
        item.setLabel(className);
        item.setDetail(packageName);
        item.setInsertText(insertText);
        item.setInsertTextFormat(InsertTextFormat.Snippet);
        item.setCommand(TRIGGER_SIGNATURE_HELP);
        item.setFilterText(className);
        item.setAdditionalTextEdits(addImport(qualifiedName));
        item.setSortText("3/" + className);
        item.setData(key);

        return item;
    }

    private CompletionItem completeClassNameFromClassPath(String qualifiedName) {
        CompletionItem item = new CompletionItem();
        String packageName = mostIds(qualifiedName), simpleName = lastId(qualifiedName);

        item.setLabel(simpleName);
        item.setDetail(packageName);
        item.setInsertText(simpleName);
        item.setAdditionalTextEdits(addImport(qualifiedName));
        item.setSortText("3/" + simpleName);
        item.setData(qualifiedName);

        // TODO implement vscode resolve-completion-item

        return item;
    }

    private Stream<CompletionItem> completionItem(Element e) {
        try {
            String name = e.getSimpleName().toString();

            switch (e.getKind()) {
                case PACKAGE:
                    {
                        PackageElement p = (PackageElement) e;
                        CompletionItem item = new CompletionItem();
                        String id = lastId(p.getSimpleName().toString());

                        item.setKind(CompletionItemKind.Module);
                        item.setLabel(id);
                        item.setInsertText(id);
                        item.setSortText("1/" + id);

                        return Stream.of(item);
                    }
                case ENUM:
                case INTERFACE:
                case ANNOTATION_TYPE:
                case CLASS:
                    {
                        TypeElement type = (TypeElement) e;
                        int order = isAlreadyImported(type.getQualifiedName().toString()) ? 1 : 2;
                        CompletionItem item = new CompletionItem();

                        item.setKind(classKind(e.getKind()));
                        item.setLabel(name);
                        item.setDetail(type.getQualifiedName().toString());
                        item.setInsertText(name);

                        PackageElement classPackage = elements.getPackageOf(e);
                        if (classPackage != null)
                            item.setDetail(classPackage.getQualifiedName().toString());

                        item.setAdditionalTextEdits(
                                addImport(((TypeElement) e).getQualifiedName().toString()));
                        item.setSortText(order + "/" + name);
                        item.setData(type.getQualifiedName().toString());

                        return Stream.of(item);
                    }
                case TYPE_PARAMETER:
                    {
                        CompletionItem item = new CompletionItem();

                        item.setKind(CompletionItemKind.Reference);
                        item.setLabel(name);
                        item.setSortText("1/" + name);

                        return Stream.of(item);
                    }
                case ENUM_CONSTANT:
                    {
                        CompletionItem item = new CompletionItem();

                        item.setKind(CompletionItemKind.Enum);
                        item.setLabel(name);
                        item.setDetail(e.getEnclosingElement().getSimpleName().toString());
                        item.setSortText("1/" + name);

                        return Stream.of(item);
                    }
                case FIELD:
                    {
                        CompletionItem item = new CompletionItem();
                        boolean isField = e.getEnclosingElement().getKind() == ElementKind.CLASS;

                        item.setKind(
                                isField
                                        ? CompletionItemKind.Property
                                        : CompletionItemKind.Variable);
                        item.setLabel(name);
                        item.setDetail(ShortTypePrinter.print(e.asType()));
                        item.setSortText(String.format("%s/%s", isField ? 1 : 0, name));

                        return Stream.of(item);
                    }
                case PARAMETER:
                case LOCAL_VARIABLE:
                case EXCEPTION_PARAMETER:
                    {
                        CompletionItem item = new CompletionItem();

                        item.setKind(CompletionItemKind.Variable);
                        item.setLabel(name);
                        item.setSortText("1/" + name);

                        return Stream.of(item);
                    }
                case METHOD:
                    {
                        ExecutableElement method = (ExecutableElement) e;
                        CompletionItem item = new CompletionItem();

                        item.setKind(CompletionItemKind.Method);
                        item.setLabel(name);
                        item.setDetail(Hovers.methodSignature(method, true, false));
                        if (context != CursorContext.Reference) item.setInsertText(name + "($0)");
                        item.setInsertTextFormat(InsertTextFormat.Snippet);
                        item.setCommand(TRIGGER_SIGNATURE_HELP);
                        item.setFilterText(name);
                        item.setSortText("1/" + name);
                        item.setData(docs.methodKey(method));

                        return Stream.of(item);
                    }
                case CONSTRUCTOR:
                    {
                        TypeElement enclosingClass = (TypeElement) e.getEnclosingElement();
                        int order =
                                isAlreadyImported(enclosingClass.getQualifiedName().toString())
                                        ? 2
                                        : 3;
                        name = enclosingClass.getSimpleName().toString();

                        ExecutableElement method = (ExecutableElement) e;
                        CompletionItem item = new CompletionItem();
                        String insertText = name;

                        if (!enclosingClass.getTypeParameters().isEmpty()) insertText += "<>";

                        insertText += "($0)";

                        item.setKind(CompletionItemKind.Constructor);
                        item.setLabel(name);
                        item.setDetail(Hovers.methodSignature(method, false, false));
                        item.setInsertText(insertText);
                        item.setInsertTextFormat(InsertTextFormat.Snippet);
                        item.setCommand(TRIGGER_SIGNATURE_HELP);
                        item.setFilterText(name);
                        item.setAdditionalTextEdits(
                                addImport(enclosingClass.getQualifiedName().toString()));
                        item.setSortText(order + "/" + name);
                        item.setData(docs.methodKey(method));

                        return Stream.of(item);
                    }
                case STATIC_INIT:
                case INSTANCE_INIT:
                case OTHER:
                case RESOURCE_VARIABLE:
                default:
                    // Nothing user-enterable
                    // Nothing user-enterable
                    return Stream.empty();
            }
        } catch (Symbol.CompletionFailure failed) {
            LOG.warning(failed.getMessage());

            return Stream.empty();
        }
    }

    private CompletionItemKind classKind(ElementKind kind) {
        switch (kind) {
            case CLASS:
                return CompletionItemKind.Class;
            case ANNOTATION_TYPE:
            case INTERFACE:
                return CompletionItemKind.Interface;
            case ENUM:
                return CompletionItemKind.Enum;
            default:
                throw new RuntimeException("Expected CLASS, INTERFACE or ENUM but found " + kind);
        }
    }

    private List<TextEdit> addImport(String qualifiedName) {
        if (!isAlreadyImported(qualifiedName) && CursorContext.from(path) != CursorContext.Import)
            return new RefactorFile(task, compilationUnit)
                    .addImport(mostIds(qualifiedName), lastId(qualifiedName));
        else return Collections.emptyList();
    }

    public static boolean containsCharactersInOrder(
            CharSequence candidate, CharSequence pattern, boolean caseSensitive) {
        int iCandidate = 0, iPattern = 0;

        while (iCandidate < candidate.length() && iPattern < pattern.length()) {
            char patternChar = pattern.charAt(iPattern);
            char testChar = candidate.charAt(iCandidate);

            if (!caseSensitive) {
                patternChar = Character.toLowerCase(patternChar);
                testChar = Character.toLowerCase(testChar);
            }

            if (patternChar == testChar) {
                iPattern++;
                iCandidate++;
            } else iCandidate++;
        }

        return iPattern == pattern.length();
    }

    private static final Logger LOG = Logger.getLogger("main");
}
