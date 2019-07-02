package org.javacs;

import com.google.gson.*;
import com.sun.source.doctree.*;
import com.sun.source.tree.*;
import com.sun.source.util.TreePath;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;
import java.util.logging.Logger;
import javax.lang.model.element.*;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.javacs.lsp.*;

class JavaLanguageServer extends LanguageServer {
    // TODO allow multiple workspace roots
    private Path workspaceRoot;
    private final LanguageClient client;
    private JavaCompilerService cacheCompiler;
    private JsonObject cacheSettings;
    private JsonObject settings = new JsonObject();

    JavaCompilerService compiler() {
        if (!settings.equals(cacheSettings)) {
            LOG.info("Recreating compiler because\n\t" + settings + "\nis different than\n\t" + cacheSettings);
            cacheCompiler = createCompiler();
            cacheSettings = settings;
        }
        return cacheCompiler;
    }

    private static int severity(Diagnostic.Kind kind) {
        switch (kind) {
            case ERROR:
                return DiagnosticSeverity.Error;
            case WARNING:
            case MANDATORY_WARNING:
                return DiagnosticSeverity.Warning;
            case NOTE:
                return DiagnosticSeverity.Information;
            case OTHER:
            default:
                return DiagnosticSeverity.Hint;
        }
    }

    private static Position position(String content, long offset) {
        int line = 0, column = 0;
        for (int i = 0; i < offset; i++) {
            if (content.charAt(i) == '\n') {
                line++;
                column = 0;
            } else column++;
        }
        return new Position(line, column);
    }

    void publishDiagnostics(Collection<URI> files, List<Diagnostic<? extends JavaFileObject>> javaDiagnostics) {
        var byUri = new HashMap<URI, List<org.javacs.lsp.Diagnostic>>();
        for (var j : javaDiagnostics) {
            if (j.getSource() == null) {
                LOG.warning("No source in warning " + j.getMessage(null));
                continue;
            }
            // Check that error is in an open file
            var uri = j.getSource().toUri();
            if (!files.contains(uri)) {
                continue;
            }
            // Find start and end position
            var content = FileStore.contents(uri);
            var start = position(content, j.getStartPosition());
            var end = position(content, j.getEndPosition());
            var d = new org.javacs.lsp.Diagnostic();
            d.severity = severity(j.getKind());
            d.range = new Range(start, end);
            d.code = j.getCode();
            d.message = j.getMessage(null);
            if (j.getCode().equals("unused")) {
                d.tags = List.of(DiagnosticTag.Unnecessary);
            }
            // Add to byUri
            var ds = byUri.computeIfAbsent(uri, __ -> new ArrayList<>());
            ds.add(d);
        }

        for (var f : files) {
            var ds = byUri.getOrDefault(f, List.of());
            var message = new PublishDiagnosticsParams(f, ds);
            client.publishDiagnostics(message);
        }
    }

    void lint(Collection<URI> uris) {
        // TODO only lint the current focus, merging errors/decorations with existing
        LOG.info("Lint " + Profiler.describe(uris) + "...");
        var started = Instant.now();
        if (uris.isEmpty()) return;
        try (var batch = compiler().compileUris(uris)) {
            // Report compilation errors
            var messages = batch.reportErrors();
            publishDiagnostics(uris, messages);
            uncheckedChanges = false;
        }
        var elapsed = Duration.between(started, Instant.now());
        LOG.info(String.format("...done linting in %d ms", elapsed.toMillis()));
    }

    private static final Gson gson = new Gson();

    private void javaStartProgress(JavaStartProgressParams params) {
        client.customNotification("java/startProgress", gson.toJsonTree(params));
    }

    private void javaReportProgress(JavaReportProgressParams params) {
        client.customNotification("java/reportProgress", gson.toJsonTree(params));
    }

    private void javaEndProgress() {
        client.customNotification("java/endProgress", JsonNull.INSTANCE);
    }

    private JavaCompilerService createCompiler() {
        Objects.requireNonNull(workspaceRoot, "Can't create compiler because workspaceRoot has not been initialized");

        javaStartProgress(new JavaStartProgressParams("Configure javac"));
        javaReportProgress(new JavaReportProgressParams("Finding source roots"));

        var externalDependencies = externalDependencies();
        var classPath = classPath();
        var addExports = addExports();
        // If classpath is specified by the user, don't infer anything
        if (!classPath.isEmpty()) {
            javaEndProgress();
            return new JavaCompilerService(classPath, Collections.emptySet(), addExports);
        }
        // Otherwise, combine inference with user-specified external dependencies
        else {
            var infer = new InferConfig(workspaceRoot, externalDependencies);

            javaReportProgress(new JavaReportProgressParams("Inferring class path"));
            classPath = infer.classPath();

            javaReportProgress(new JavaReportProgressParams("Inferring doc path"));
            var docPath = infer.buildDocPath();

            javaEndProgress();
            return new JavaCompilerService(classPath, docPath, addExports);
        }
    }

    private Set<String> externalDependencies() {
        if (!settings.has("externalDependencies")) return Set.of();
        var array = settings.getAsJsonArray("externalDependencies");
        var strings = new HashSet<String>();
        for (var each : array) {
            strings.add(each.getAsString());
        }
        return strings;
    }

    private Set<Path> classPath() {
        if (!settings.has("classPath")) return Set.of();
        var array = settings.getAsJsonArray("classPath");
        var paths = new HashSet<Path>();
        for (var each : array) {
            paths.add(Paths.get(each.getAsString()).toAbsolutePath());
        }
        return paths;
    }

    private Set<String> addExports() {
        if (!settings.has("addExports")) return Set.of();
        var array = settings.getAsJsonArray("addExports");
        var strings = new HashSet<String>();
        for (var each : array) {
            strings.add(each.getAsString());
        }
        return strings;
    }

    @Override
    public InitializeResult initialize(InitializeParams params) {
        this.workspaceRoot = Paths.get(params.rootUri);
        FileStore.setWorkspaceRoots(Set.of(Paths.get(params.rootUri)));

        var c = new JsonObject();
        c.addProperty("textDocumentSync", 2); // Incremental
        c.addProperty("hoverProvider", true);
        var completionOptions = new JsonObject();
        completionOptions.addProperty("resolveProvider", true);
        var triggerCharacters = new JsonArray();
        triggerCharacters.add(".");
        completionOptions.add("triggerCharacters", triggerCharacters);
        c.add("completionProvider", completionOptions);
        var signatureHelpOptions = new JsonObject();
        var signatureTrigger = new JsonArray();
        signatureTrigger.add("(");
        signatureTrigger.add(",");
        signatureHelpOptions.add("triggerCharacters", signatureTrigger);
        c.add("signatureHelpProvider", signatureHelpOptions);
        c.addProperty("referencesProvider", true);
        c.addProperty("definitionProvider", true);
        c.addProperty("workspaceSymbolProvider", true);
        c.addProperty("documentSymbolProvider", true);
        c.addProperty("documentFormattingProvider", true);
        var codeLensOptions = new JsonObject();
        codeLensOptions.addProperty("resolveProvider", true);
        c.add("codeLensProvider", codeLensOptions);
        c.addProperty("foldingRangeProvider", true);

        return new InitializeResult(c);
    }

    @Override
    public void initialized() {
        // Register for didChangeWatchedFiles notifications
        var options = new JsonObject();
        var watchers = new JsonArray();
        var watchJava = new JsonObject();
        watchJava.addProperty("globPattern", "**/*.java");
        watchers.add(watchJava);
        options.add("watchers", watchers);
        client.registerCapability("workspace/didChangeWatchedFiles", gson.toJsonTree(options));
    }

    @Override
    public void shutdown() {}

    public JavaLanguageServer(LanguageClient client) {
        this.client = client;
    }

    @Override
    public List<SymbolInformation> workspaceSymbols(WorkspaceSymbolParams params) {
        return compiler().findSymbols(params.query, 50);
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams change) {
        settings = change.settings.getAsJsonObject();
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        // TODO update config when pom.xml changes
        for (var c : params.changes) {
            if (!FileStore.isJavaFile(c.uri)) continue;
            var file = Paths.get(c.uri);
            switch (c.type) {
                case FileChangeType.Created:
                    FileStore.externalCreate(file);
                    break;
                case FileChangeType.Changed:
                    FileStore.externalChange(file);
                    break;
                case FileChangeType.Deleted:
                    FileStore.externalDelete(file);
                    break;
            }
        }
    }

    private Integer completionItemKind(Element e) {
        switch (e.getKind()) {
            case ANNOTATION_TYPE:
                return CompletionItemKind.Interface;
            case CLASS:
                return CompletionItemKind.Class;
            case CONSTRUCTOR:
                return CompletionItemKind.Constructor;
            case ENUM:
                return CompletionItemKind.Enum;
            case ENUM_CONSTANT:
                return CompletionItemKind.EnumMember;
            case EXCEPTION_PARAMETER:
                return CompletionItemKind.Property;
            case FIELD:
                return CompletionItemKind.Field;
            case STATIC_INIT:
            case INSTANCE_INIT:
                return CompletionItemKind.Function;
            case INTERFACE:
                return CompletionItemKind.Interface;
            case LOCAL_VARIABLE:
                return CompletionItemKind.Variable;
            case METHOD:
                return CompletionItemKind.Method;
            case PACKAGE:
                return CompletionItemKind.Module;
            case PARAMETER:
                return CompletionItemKind.Property;
            case RESOURCE_VARIABLE:
                return CompletionItemKind.Variable;
            case TYPE_PARAMETER:
                return CompletionItemKind.TypeParameter;
            case OTHER:
            default:
                return null;
        }
    }

    private boolean isMemberOfObject(Element e) {
        var parent = e.getEnclosingElement();
        if (parent instanceof TypeElement) {
            var type = (TypeElement) parent;
            return type.getQualifiedName().contentEquals("java.lang.Object");
        }
        return false;
    }

    /** Cache of completions from the last call to `completion` */
    private final Map<String, Completion> lastCompletions = new HashMap<>();

    @Override
    public Optional<CompletionList> completion(TextDocumentPositionParams position) {
        var started = Instant.now();
        var uri = position.textDocument.uri;
        if (!FileStore.isJavaFile(uri)) return Optional.empty();
        var line = position.position.line + 1;
        var column = position.position.character + 1;
        LOG.info(String.format("Complete at %s(%d,%d)", uri.getPath(), line, column));
        // Figure out what kind of completion we want to do
        var maybeCtx = compiler().parseFile(uri).completionContext(line, column);
        // TODO don't complete inside of comments
        if (!maybeCtx.isPresent()) {
            var items = new ArrayList<CompletionItem>();
            for (var name : CompileBatch.TOP_LEVEL_KEYWORDS) {
                var i = new CompletionItem();
                i.label = name;
                i.kind = CompletionItemKind.Keyword;
                i.detail = "keyword";
                items.add(i);
            }
            return Optional.of(new CompletionList(true, items));
        }
        // Compile again, focusing on a region that depends on what type of completion we want to do
        var ctx = maybeCtx.get();
        List<Completion> cs;
        boolean isIncomplete;
        try (var focus = compiler().compileFocus(uri, ctx.line, ctx.character)) {
            // Do a specific type of completion
            switch (ctx.kind) {
                case MemberSelect:
                    cs = focus.completeMembers(uri, ctx.line, ctx.character, false);
                    isIncomplete = false;
                    break;
                case MemberReference:
                    cs = focus.completeMembers(uri, ctx.line, ctx.character, true);
                    isIncomplete = false;
                    break;
                case Identifier:
                    cs =
                            focus.completeIdentifiers(
                                    uri, ctx.line, ctx.character, ctx.inClass, ctx.inMethod, ctx.partialName);
                    isIncomplete = cs.size() >= CompileBatch.MAX_COMPLETION_ITEMS;
                    break;
                case Annotation:
                    cs = focus.completeAnnotations(uri, ctx.line, ctx.character, ctx.partialName);
                    isIncomplete = cs.size() >= CompileBatch.MAX_COMPLETION_ITEMS;
                    break;
                case Case:
                    cs = focus.completeCases(uri, ctx.line, ctx.character);
                    isIncomplete = false;
                    break;
                default:
                    throw new RuntimeException("Unexpected completion context " + ctx.kind);
            }
        }
        // Convert to CompletionItem
        var result = new ArrayList<CompletionItem>();
        for (var c : cs) {
            var i = new CompletionItem();
            var id = UUID.randomUUID().toString();
            i.data = new JsonPrimitive(id);
            lastCompletions.put(id, c);
            if (c.element != null) {
                i.label = c.element.getSimpleName().toString();
                i.kind = completionItemKind(c.element);
                // Detailed name will be resolved later, using docs to fill in method names
                if (!(c.element instanceof ExecutableElement)) {
                    i.detail = ShortTypePrinter.print(c.element.asType());
                }
                // TODO prioritize based on usage?
                // TODO prioritize based on scope
                if (isMemberOfObject(c.element)) {
                    i.sortText = 9 + i.label;
                } else {
                    i.sortText = 2 + i.label;
                }
            } else if (c.packagePart != null) {
                i.label = c.packagePart.name;
                i.kind = CompletionItemKind.Module;
                i.detail = c.packagePart.fullName;
                i.sortText = 2 + i.label;
            } else if (c.keyword != null) {
                i.label = c.keyword;
                i.kind = CompletionItemKind.Keyword;
                i.detail = "keyword";
                i.sortText = 3 + i.label;
            } else if (c.className != null) {
                i.label = StringSearch.lastName(c.className.name);
                i.kind = CompletionItemKind.Class;
                i.detail = c.className.name;
                if (c.className.isImported) {
                    i.sortText = 2 + i.label;
                } else {
                    i.sortText = 4 + i.label;
                }
            } else if (c.snippet != null) {
                i.label = c.snippet.label;
                i.kind = CompletionItemKind.Snippet;
                i.insertText = c.snippet.snippet;
                i.insertTextFormat = InsertTextFormat.Snippet;
                i.sortText = 1 + i.label;
            } else {
                throw new RuntimeException(c + " is not valid");
            }

            result.add(i);
        }
        // Log timing
        var elapsedMs = Duration.between(started, Instant.now()).toMillis();
        if (isIncomplete) LOG.info(String.format("Found %d items (incomplete) in %,d ms", result.size(), elapsedMs));
        else LOG.info(String.format("...found %d items in %,d ms", result.size(), elapsedMs));

        return Optional.of(new CompletionList(isIncomplete, result));
    }

    private Optional<MarkupContent> findDocs(Ptr ptr) {
        LOG.info(String.format("Find docs for `%s`...", ptr));

        // Find el in the doc path
        var file = compiler().docs().find(ptr);
        if (!file.isPresent()) return Optional.empty();
        // Parse file and find el
        var parse = compiler().parseJavaFileObject(file.get());
        var path = parse.fuzzyFind(ptr);
        if (!path.isPresent()) return Optional.empty();
        // Parse the doctree associated with el
        var docTree = parse.doc(path.get());
        ;
        var string = asMarkupContent(docTree);
        return Optional.of(string);
    }

    private Optional<String> findMethodDetails(ExecutableElement method) {
        LOG.info(String.format("Find details for method `%s`...", method));

        // TODO find and parse happens twice between findDocs and findMethodDetails
        // Find method in the doc path
        var ptr = new Ptr(method);
        var file = compiler().docs().find(ptr);
        if (!file.isPresent()) return Optional.empty();
        // Parse file and find method
        var parse = compiler().parseJavaFileObject(file.get());
        var path = parse.fuzzyFind(ptr);
        if (!path.isPresent()) return Optional.empty();
        // Should be a MethodTree
        var tree = path.get().getLeaf();
        if (!(tree instanceof MethodTree)) {
            LOG.warning(String.format("...method `%s` associated with non-method tree `%s`", method, tree));
            return Optional.empty();
        }
        // Write description of method using info from source
        var methodTree = (MethodTree) tree;
        var args = new StringJoiner(", ");
        for (var p : methodTree.getParameters()) {
            args.add(p.getName());
        }
        var details = String.format("%s %s(%s)", methodTree.getReturnType(), methodTree.getName(), args);
        return Optional.of(details);
    }

    private String defaultDetails(ExecutableElement method) {
        var args = new StringJoiner(", ");
        var missingParamNames =
                method.getParameters().stream().allMatch(p -> p.getSimpleName().toString().matches("arg\\d+"));
        for (var p : method.getParameters()) {
            if (missingParamNames) args.add(ShortTypePrinter.print(p.asType()));
            else args.add(p.getSimpleName().toString());
        }
        return String.format("%s %s(%s)", ShortTypePrinter.print(method.getReturnType()), method.getSimpleName(), args);
    }

    private String asMarkdown(List<? extends DocTree> lines) {
        var join = new StringJoiner("\n");
        for (var l : lines) join.add(l.toString());
        var html = join.toString();
        return Docs.htmlToMarkdown(html);
    }

    private String asMarkdown(DocCommentTree comment) {
        var lines = comment.getFirstSentence();
        return asMarkdown(lines);
    }

    private MarkupContent asMarkupContent(DocCommentTree comment) {
        var markdown = asMarkdown(comment);
        var content = new MarkupContent();
        content.kind = MarkupKind.Markdown;
        content.value = markdown;
        return content;
    }

    @Override
    public CompletionItem resolveCompletionItem(CompletionItem unresolved) {
        if (unresolved.data == null) return unresolved;
        var idJson = (JsonPrimitive) unresolved.data;
        var id = idJson.getAsString();
        var cached = lastCompletions.get(id);
        if (cached == null) {
            LOG.warning("CompletionItem " + id + " was not in the cache");
            return unresolved;
        }
        if (cached.element != null) {
            if (cached.element instanceof ExecutableElement) {
                var method = (ExecutableElement) cached.element;
                unresolved.detail = findMethodDetails(method).orElse(defaultDetails(method));
            }
            var markdown = findDocs(new Ptr(cached.element));
            if (markdown.isPresent()) {
                unresolved.documentation = markdown.get();
            }
        } else if (cached.className != null) {
            var packageName = StringSearch.mostName(cached.className.name);
            var className = StringSearch.lastName(cached.className.name);
            var ptr = Ptr.toClass(packageName, className);
            var markdown = findDocs(ptr);
            if (markdown.isPresent()) unresolved.documentation = markdown.get();
        }
        return unresolved;
    }

    private String hoverTypeDeclaration(TypeElement t) {
        var result = new StringBuilder();
        switch (t.getKind()) {
            case ANNOTATION_TYPE:
                result.append("@interface");
                break;
            case INTERFACE:
                result.append("interface");
                break;
            case CLASS:
                result.append("class");
                break;
            case ENUM:
                result.append("enum");
                break;
            default:
                LOG.warning("Don't know what to call type element " + t);
                result.append("???");
        }
        result.append(" ").append(ShortTypePrinter.print(t.asType()));
        var superType = ShortTypePrinter.print(t.getSuperclass());
        switch (superType) {
            case "Object":
            case "none":
                break;
            default:
                result.append(" extends ").append(superType);
        }
        return result.toString();
    }

    private String hoverCode(Element e) {
        if (e instanceof ExecutableElement) {
            var m = (ExecutableElement) e;
            return ShortTypePrinter.printMethod(m);
        } else if (e instanceof VariableElement) {
            var v = (VariableElement) e;
            return ShortTypePrinter.print(v.asType()) + " " + v;
        } else if (e instanceof TypeElement) {
            var t = (TypeElement) e;
            var lines = new StringJoiner("\n");
            lines.add(hoverTypeDeclaration(t) + " {");
            for (var member : t.getEnclosedElements()) {
                // TODO check accessibility
                if (member instanceof ExecutableElement || member instanceof VariableElement) {
                    lines.add("  " + hoverCode(member) + ";");
                } else if (member instanceof TypeElement) {
                    lines.add("  " + hoverTypeDeclaration((TypeElement) member) + " { /* removed */ }");
                }
            }
            lines.add("}");
            return lines.toString();
        } else {
            return e.toString();
        }
    }

    private Optional<String> hoverDocs(Element e) {
        var ptr = new Ptr(e);
        var file = compiler().docs().find(ptr);
        if (!file.isPresent()) return Optional.empty();
        var parse = compiler().parseJavaFileObject(file.get());
        var path = parse.fuzzyFind(ptr);
        if (!path.isPresent()) return Optional.empty();
        var doc = parse.doc(path.get());
        var md = asMarkdown(doc);
        return Optional.of(md);
    }

    @Override
    public Optional<Hover> hover(TextDocumentPositionParams position) {
        var uri = position.textDocument.uri;
        var line = position.position.line + 1;
        var column = position.position.character + 1;
        if (!FileStore.isJavaFile(uri)) return Optional.empty();
        // Log start time
        LOG.info(String.format("Hover over %s(%d,%d) ...", uri.getPath(), line, column));
        var started = Instant.now();
        // Compile entire file
        try (var compile = compiler().compileFile(uri)) {
            // Find element under cursor
            var el = compile.element(uri, line, column);
            if (!el.isPresent()) {
                LOG.info("...no element under cursor");
                return Optional.empty();
            }
            // Result is combination of docs and code
            var result = new ArrayList<MarkedString>();
            // Add docs hover message
            var docs = hoverDocs(el.get());
            docs.filter(Predicate.not(String::isBlank))
                    .ifPresent(
                            doc -> {
                                result.add(new MarkedString(doc));
                            });

            // Add code hover message
            var code = hoverCode(el.get());
            result.add(new MarkedString("java.hover", code));
            // Log duration
            var elapsed = Duration.between(started, Instant.now());
            LOG.info(String.format("...found hover in %d ms", elapsed.toMillis()));

            return Optional.of(new Hover(result));
        }
    }

    private SignatureInformation asSignatureInformation(ExecutableElement e) {
        // Figure out parameter info from source or from ExecutableElement
        var i = new SignatureInformation();
        var ptr = new Ptr(e);
        var ps = signatureParamsFromDocs(ptr).orElse(signatureParamsFromMethod(e));
        i.parameters = ps;

        // Compute label from params (which came from either source or ExecutableElement)
        var name = e.getSimpleName();
        if (name.contentEquals("<init>")) name = e.getEnclosingElement().getSimpleName();
        var args = new StringJoiner(", ");
        for (var p : ps) {
            args.add(p.label);
        }
        i.label = name + "(" + args + ")";

        return i;
    }

    private List<ParameterInformation> signatureParamsFromMethod(ExecutableElement e) {
        var missingParamNames = ShortTypePrinter.missingParamNames(e);
        var ps = new ArrayList<ParameterInformation>();
        for (var v : e.getParameters()) {
            var p = new ParameterInformation();
            if (missingParamNames) p.label = ShortTypePrinter.print(v.asType());
            else p.label = v.getSimpleName().toString();
            ps.add(p);
        }
        return ps;
    }

    private Optional<List<ParameterInformation>> signatureParamsFromDocs(Ptr ptr) {
        // Find the file ptr point to, and parse it
        var file = compiler().docs().find(ptr);
        if (!file.isPresent()) return Optional.empty();
        var parse = compiler().parseJavaFileObject(file.get());
        // Find the tree
        var path = parse.fuzzyFind(ptr);
        if (!path.isPresent()) return Optional.empty();
        if (!(path.get().getLeaf() instanceof MethodTree)) return Optional.empty();
        var method = (MethodTree) path.get().getLeaf();
        // Find the docstring on method, or empty doc if there is none
        var doc = parse.doc(path.get());
        // Get param docs from @param tags
        var ps = new ArrayList<ParameterInformation>();
        var paramComments = new HashMap<String, String>();
        for (var tag : doc.getBlockTags()) {
            if (tag.getKind() == DocTree.Kind.PARAM) {
                var param = (ParamTree) tag;
                paramComments.put(param.getName().toString(), asMarkdown(param.getDescription()));
            }
        }
        // Get param names from source
        for (var param : method.getParameters()) {
            var info = new ParameterInformation();
            var name = param.getName().toString();
            info.label = name;
            if (paramComments.containsKey(name)) {
                var markdown = paramComments.get(name);
                info.documentation = new MarkupContent("markdown", markdown);
            } else {
                var markdown = Objects.toString(param.getType(), "");
                info.documentation = new MarkupContent("markdown", markdown);
            }
            ps.add(info);
        }
        return Optional.of(ps);
    }

    private SignatureHelp asSignatureHelp(MethodInvocation invoke) {
        // TODO use docs to get parameter names
        var sigs = new ArrayList<SignatureInformation>();
        for (var e : invoke.overloads) {
            sigs.add(asSignatureInformation(e));
        }
        var activeSig = invoke.activeMethod.map(invoke.overloads::indexOf).orElse(0);
        return new SignatureHelp(sigs, activeSig, invoke.activeParameter);
    }

    @Override
    public Optional<SignatureHelp> signatureHelp(TextDocumentPositionParams position) {
        var uri = position.textDocument.uri;
        if (!FileStore.isJavaFile(uri)) return Optional.empty();
        var line = position.position.line + 1;
        var column = position.position.character + 1;
        try (var focus = compiler().compileFocus(uri, line, column)) {
            var help = focus.methodInvocation(uri, line, column).map(this::asSignatureHelp);
            return help;
        }
    }

    @Override
    public Optional<List<Location>> gotoDefinition(TextDocumentPositionParams position) {
        var fromUri = position.textDocument.uri;
        if (!FileStore.isJavaFile(fromUri)) return Optional.empty();
        var fromLine = position.position.line + 1;
        var fromColumn = position.position.character + 1;

        // Compile from-file and identify element under cursor
        LOG.info(String.format("Go-to-def at %s:%d...", fromUri, fromLine));
        Optional<Element> toEl;
        try (var compile = compiler().compileFile(fromUri)) {
            toEl = compile.element(fromUri, fromLine, fromColumn);
            if (!toEl.isPresent()) {
                LOG.info(String.format("...no element at cursor"));
                return Optional.empty();
            }
        }

        // Compile all files that *might* contain definitions of fromEl
        var toFiles = compiler().potentialDefinitions(toEl.get());
        toFiles.add(fromUri);
        try (var batch = compiler().compileBatch(pruneWord(toFiles, toEl.get()))) {
            // Find fromEl again, so that we have an Element from the current batch
            var fromElAgain = batch.element(fromUri, fromLine, fromColumn).get();

            // Find all definitions of fromElAgain
            var toTreePaths = batch.definitions(fromElAgain);
            if (!toTreePaths.isPresent()) return Optional.empty();
            var result = new ArrayList<Location>();
            for (var path : toTreePaths.get()) {
                var toUri = path.getCompilationUnit().getSourceFile().toUri();
                var toRange = batch.range(path);
                if (!toRange.isPresent()) {
                    LOG.warning(String.format("Couldn't locate `%s`", path.getLeaf()));
                    continue;
                }
                var from = new Location(toUri, toRange.get());
                result.add(from);
            }
            return Optional.of(result);
        }
    }

    @Override
    public Optional<List<Location>> findReferences(ReferenceParams position) {
        var toUri = position.textDocument.uri;
        if (!FileStore.isJavaFile(toUri)) return Optional.empty();
        var toLine = position.position.line + 1;
        var toColumn = position.position.character + 1;

        // Compile from-file and identify element under cursor
        LOG.warning(String.format("Looking for references to %s(%d,%d)...", toUri.getPath(), toLine, toColumn));
        Optional<Element> toEl;
        try (var compile = compiler().compileFile(toUri)) {
            toEl = compile.element(toUri, toLine, toColumn);
            if (!toEl.isPresent()) {
                LOG.warning("...no element under cursor");
                return Optional.empty();
            }
        }

        // Compile all files that *might* contain references to toEl
        var fromUris = compiler().potentialReferences(toEl.get());
        fromUris.add(toUri);
        try (var batch = compiler().compileBatch(pruneWord(fromUris, toEl.get()))) {
            // Find toEl again, so that we have an Element from the current batch
            var toElAgain = batch.element(toUri, toLine, toColumn).get();

            // Find all references to toElAgain
            var fromTreePaths = batch.references(toElAgain);
            if (!fromTreePaths.isPresent()) return Optional.empty();
            var result = new ArrayList<Location>();
            for (var path : fromTreePaths.get()) {
                var fromUri = path.getCompilationUnit().getSourceFile().toUri();
                var fromRange = batch.range(path);
                if (!fromRange.isPresent()) {
                    LOG.warning(String.format("Couldn't locate `%s`", path.getLeaf()));
                    continue;
                }
                var from = new Location(fromUri, fromRange.get());
                result.add(from);
            }
            return Optional.of(result);
        }
    }

    private List<JavaFileObject> pruneWord(Collection<URI> files, Element el) {
        var name = el.getSimpleName().toString();
        if (name.equals("<init>")) name = el.getEnclosingElement().getSimpleName().toString();
        var sources = new ArrayList<JavaFileObject>();
        for (var f : files) {
            var pruned = Pruner.prune(f, name);
            sources.add(new SourceFileObject(f, pruned, Instant.now()));
        }
        return sources;
    }

    private ParseFile cacheParse;
    private URI cacheParseFile = URI.create("file:///NONE");;
    private int cacheParseVersion = -1;

    private void updateCachedParse(URI file) {
        if (file.equals(cacheParseFile) && FileStore.version(file) == cacheParseVersion) return;
        LOG.info(String.format("Updating cached parse file to %s", file));
        cacheParse = compiler().parseFile(file);
        cacheParseFile = file;
        cacheParseVersion = FileStore.version(file);
    }

    @Override
    public List<SymbolInformation> documentSymbol(DocumentSymbolParams params) {
        var uri = params.textDocument.uri;
        if (!FileStore.isJavaFile(uri)) return List.of();
        updateCachedParse(uri);
        var infos = cacheParse.documentSymbols();
        return infos;
    }

    @Override
    public List<CodeLens> codeLens(CodeLensParams params) {
        // TODO just create a blank code lens on every method, then resolve it async
        var uri = params.textDocument.uri;
        if (!FileStore.isJavaFile(uri)) return List.of();
        updateCachedParse(uri);
        var declarations = cacheParse.declarations();
        var result = new ArrayList<CodeLens>();
        for (var d : declarations) {
            var range = cacheParse.range(d);
            if (!range.isPresent()) continue;
            var className = JavaCompilerService.className(d);
            var memberName = JavaCompilerService.memberName(d);
            // If test class or method, add "Run Test" code lens
            if (cacheParse.isTestClass(d)) {
                var arguments = new JsonArray();
                arguments.add(uri.toString());
                arguments.add(className);
                arguments.add(JsonNull.INSTANCE);
                var command = new Command("Run All Tests", "java.command.test.run", arguments);
                var lens = new CodeLens(range.get(), command, null);
                result.add(lens);
                // TODO run all tests in file
                // TODO run all tests in package
            }
            if (cacheParse.isTestMethod(d)) {
                var arguments = new JsonArray();
                arguments.add(uri.toString());
                arguments.add(className);
                if (memberName.isPresent()) arguments.add(memberName.get());
                else arguments.add(JsonNull.INSTANCE);
                var command = new Command("Run Test", "java.command.test.run", arguments);
                var lens = new CodeLens(range.get(), command, null);
                result.add(lens);
            }
            if (!cacheParse.isTestMethod(d) && !cacheParse.isTestClass(d) && !cacheParse.isOverride(d)) {
                // Unresolved "_ references" code lens
                var start = range.get().start;
                var line = start.line;
                var character = start.character;
                var data = new JsonArray();
                data.add("java.command.findReferences");
                data.add(uri.toString());
                data.add(line);
                data.add(character);
                var lens = new CodeLens(range.get(), null, data);
                result.add(lens);
            }
        }
        return result;
    }

    @Override
    public CodeLens resolveCodeLens(CodeLens unresolved) {
        // Unpack data
        var data = unresolved.data;
        var command = data.get(0).getAsString();
        assert command.equals("java.command.findReferences");
        var uriString = data.get(1).getAsString();
        var uri = URI.create(uriString);
        var line = data.get(2).getAsInt() + 1;
        var character = data.get(3).getAsInt() + 1;
        // Update command
        var count = countReferences(uri, line, character);
        String title;
        if (count == -1) title = "? references";
        else if (count == 1) title = "1 reference";
        else if (count == 100) title = "Find references";
        else title = String.format("%d references", count);
        var arguments = new JsonArray();
        arguments.add(uri.toString());
        arguments.add(line - 1);
        arguments.add(character - 1);
        unresolved.command = new Command(title, command, arguments);

        return unresolved;
    }

    /**
     * cacheReferencesFile is the target of every reference currently in cacheIndex. Index#needsUpdate(_) assumes that
     * the user edits one file at a time, and checks whether edits to that file invalidate the cached index. To
     * guarantee this assumption, we simply invalidate all cached indices when the user changes files.
     */
    private URI cacheReferencesFile = URI.create("file:///NONE");
    /** cacheCountReferences[toDeclaration] is a list of all files that have references to toDeclaration */
    private final Map<Ptr, List<URI>> cacheReferences = new HashMap<>();
    /**
     * cacheCountReferences[toDeclaration] == TOO_EXPENSIVE indicates there are too many potential references to
     * toDeclaration
     */
    private static final List<URI> TOO_EXPENSIVE = new ArrayList<>();
    /** cacheIndex[fromFile] is a count of all references from fromFile to cacheCountReferencesFile */
    private final Map<URI, Index> cacheIndex = new HashMap<>();

    private boolean cacheReferencesNeedsUpdate(Ptr toPtr, Set<Ptr> signature) {
        if (!cacheReferences.containsKey(toPtr)) return true;
        for (var fromUri : cacheReferences.get(toPtr)) {
            var index = cacheIndex.get(fromUri);
            if (index.needsUpdate(signature)) return true;
        }
        return false;
    }

    private int countReferences(URI toUri, int toLine, int toColumn) {
        // If the user changes files, invalidate all cached indices
        if (!toUri.equals(cacheReferencesFile)) {
            cacheReferences.clear();
            cacheIndex.clear();
            cacheReferencesFile = toUri;
        }

        // TODO this is a bit of a mess
        Optional<Element> toEl;
        Ptr toPtr;
        Set<Ptr> signature;
        int count;
        try (var compile = compiler().compileFile(toUri)) {
            // Find the element we want to count references to
            toEl = compile.element(toUri, toLine, toColumn);
            if (!toEl.isPresent()) {
                LOG.warning("...no element at code lens");
                return -1;
            }
            toPtr = new Ptr(toEl.get());

            // Find the signature of the target file
            var declarations = compile.declarations(toUri);
            signature = new HashSet<Ptr>();
            for (var el : declarations) {
                signature.add(new Ptr(el));
            }

            // Always update active file
            var activeIndex = compile.index(toUri, declarations);
            count = activeIndex.count(toPtr);
        }

        // If the signature has changed, or the from-files contain errors, we need to redo the count
        if (cacheReferencesNeedsUpdate(toPtr, signature)) {
            LOG.info(String.format("Count references to `%s`...", toPtr));

            // Compile all files that *might* contain references to toEl
            var fromUris = compiler().potentialReferences(toEl.get());
            fromUris.remove(toUri);

            // If it's too expensive to compute the code lens
            if (fromUris.size() > 10) {
                LOG.info(
                        String.format(
                                "...there are %d potential references, which is too expensive to compile",
                                fromUris.size()));
                cacheReferences.put(toPtr, TOO_EXPENSIVE);
            } else {
                // Make sure all fromUris -> toUri references are in cacheIndex
                var list = referencesFile(fromUris, toUri, signature);
                cacheReferences.put(toPtr, list);
            }
        } else {
            LOG.info(String.format("Using cached count references to `%s`", toPtr));
        }

        // Count up references out of index
        var fromUris = cacheReferences.get(toPtr);
        if (fromUris == TOO_EXPENSIVE) return 100;
        for (var fromUri : fromUris) {
            var index = cacheIndex.get(fromUri);
            count += index.count(toPtr);
        }

        return count;
    }

    private boolean cacheIndexNeedsUpdate(URI fromUri, Set<Ptr> signature) {
        if (!cacheIndex.containsKey(fromUri)) return true;
        var index = cacheIndex.get(fromUri);
        if (index.hasErrors) {
            LOG.info(
                    String.format(
                            "...%s needs to be re-indexed because it contains errors", StringSearch.fileName(fromUri)));
            return true;
        }
        if (index.needsUpdate(signature)) {
            LOG.info(
                    String.format(
                            "...%s needs to be re-indexed because it refers to a declaration that has changed",
                            StringSearch.fileName(fromUri)));
            return true;
        }
        return false;
    }

    private List<URI> referencesFile(Collection<URI> fromUris, URI toUri, Set<Ptr> signature) {
        // Check which files need to be updated
        var outOfDate = new HashSet<URI>();
        for (var fromUri : fromUris) {
            if (cacheIndexNeedsUpdate(fromUri, signature)) {
                outOfDate.add(fromUri);
            }
        }

        // Update out-of-date indices
        if (!outOfDate.isEmpty()) {
            // Compile all files that need to be updated in a batch
            outOfDate.add(toUri);
            try (var batch = compiler().compileUris(outOfDate)) {
                // Find all declarations in toFile
                var toEls = batch.declarations(toUri);

                // Index outOfDate
                LOG.info(
                        String.format(
                                "...search for references to %d elements in %d files", toEls.size(), outOfDate.size()));
                for (var fromUri : outOfDate) {
                    var index = batch.index(fromUri, toEls);
                    cacheIndex.put(fromUri, index);
                }
            }
        } else {
            LOG.info("...all indexes are cached and up-to-date");
        }

        // Figure out which files actually reference one of the Ptrs in signature
        var actuallyReferencesFile = new ArrayList<URI>();
        for (var fromUri : fromUris) {
            var index = cacheIndex.get(fromUri);
            if (index.total() > 0) actuallyReferencesFile.add(fromUri);
        }
        return actuallyReferencesFile;
    }

    @Override
    public List<TextEdit> formatting(DocumentFormattingParams params) {
        try (var compile = compiler().compileFile(params.textDocument.uri)) {
            var edits = new ArrayList<TextEdit>();
            edits.addAll(fixImports(compile, params.textDocument.uri));
            edits.addAll(addOverrides(compile, params.textDocument.uri));
            // TODO replace var with type name when vars are copy-pasted into fields
            // TODO replace ThisClass.staticMethod() with staticMethod() when ThisClass is useless
            return edits;
        }
    }

    private List<TextEdit> fixImports(CompileBatch compile, URI file) {
        // TODO if imports already match fixed-imports, return empty list
        // TODO preserve comments and other details of existing imports
        var imports = compile.fixImports(file);
        var pos = compile.sourcePositions();
        var lines = compile.lineMap(file);
        var edits = new ArrayList<TextEdit>();
        // Delete all existing imports
        for (var i : compile.imports(file)) {
            if (!i.isStatic()) {
                var offset = pos.getStartPosition(compile.root(file), i);
                var line = (int) lines.getLineNumber(offset) - 1;
                var delete = new TextEdit(new Range(new Position(line, 0), new Position(line + 1, 0)), "");
                edits.add(delete);
            }
        }
        if (imports.isEmpty()) return edits;
        // Find a place to insert the new imports
        long insertLine = -1;
        var insertText = new StringBuilder();
        // If there are imports, use the start of the first import as the insert position
        for (var i : compile.imports(file)) {
            if (!i.isStatic() && insertLine == -1) {
                long offset = pos.getStartPosition(compile.root(file), i);
                insertLine = lines.getLineNumber(offset) - 1;
            }
        }
        // If there are no imports, insert after the package declaration
        if (insertLine == -1 && compile.root(file).getPackageName() != null) {
            long offset = pos.getEndPosition(compile.root(file), compile.root(file).getPackageName());
            insertLine = lines.getLineNumber(offset);
            insertText.append("\n");
        }
        // If there are no imports and no package, insert at the top of the file
        if (insertLine == -1) {
            insertLine = 0;
        }
        // Insert each import
        for (var i : imports) {
            insertText.append("import ").append(i).append(";\n");
        }
        var insertPosition = new Position((int) insertLine, 0);
        var insert = new TextEdit(new Range(insertPosition, insertPosition), insertText.toString());
        edits.add(insert);

        return edits;
    }

    private List<TextEdit> addOverrides(CompileBatch compile, URI file) {
        var edits = new ArrayList<TextEdit>();
        var methods = compile.needsOverrideAnnotation(file);
        var pos = compile.sourcePositions();
        var lines = compile.lineMap(file);
        for (var t : methods) {
            var methodStart = pos.getStartPosition(t.getCompilationUnit(), t.getLeaf());
            var insertLine = lines.getLineNumber(methodStart);
            var indent = methodStart - lines.getPosition(insertLine, 0);
            var insertText = new StringBuilder();
            for (var i = 0; i < indent; i++) insertText.append(' ');
            insertText.append("@Override");
            insertText.append('\n');
            var insertPosition = new Position((int) insertLine - 1, 0);
            var insert = new TextEdit(new Range(insertPosition, insertPosition), insertText.toString());
            edits.add(insert);
        }
        return edits;
    }

    @Override
    public List<FoldingRange> foldingRange(FoldingRangeParams params) {
        updateCachedParse(params.textDocument.uri);
        var folds = cacheParse.foldingRanges();
        var all = new ArrayList<FoldingRange>();

        // Merge import ranges
        if (!folds.imports.isEmpty()) {
            var merged = asFoldingRange(folds.imports.get(0), FoldingRangeKind.Imports);
            for (var i : folds.imports) {
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
        for (var t : folds.blocks) {
            all.add(asFoldingRange(t, FoldingRangeKind.Region));
        }
        for (var t : folds.comments) {
            all.add(asFoldingRange(t, FoldingRangeKind.Region));
        }

        return all;
    }

    private FoldingRange asFoldingRange(TreePath t, String kind) {
        var pos = cacheParse.sourcePositions();
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

    @Override
    public Optional<RenameResponse> prepareRename(TextDocumentPositionParams params) {
        throw new RuntimeException("TODO");
    }

    @Override
    public WorkspaceEdit rename(RenameParams params) {
        throw new RuntimeException("TODO");
    }

    @Override
    public void didOpenTextDocument(DidOpenTextDocumentParams params) {
        FileStore.open(params);
        if (FileStore.isJavaFile(params.textDocument.uri)) {
            // So that subsequent documentSymbol and codeLens requests will be faster
            updateCachedParse(params.textDocument.uri);
            uncheckedChanges = true;
        }
    }

    @Override
    public void didChangeTextDocument(DidChangeTextDocumentParams params) {
        FileStore.change(params);
        uncheckedChanges = true;
    }

    @Override
    public void didCloseTextDocument(DidCloseTextDocumentParams params) {
        FileStore.close(params);

        if (FileStore.isJavaFile(params.textDocument.uri)) {
            // Clear diagnostics
            publishDiagnostics(List.of(params.textDocument.uri), List.of());
        }
    }

    @Override
    public void didSaveTextDocument(DidSaveTextDocumentParams params) {
        if (FileStore.isJavaFile(params.textDocument.uri)) {
            // Re-lint all active documents
            lint(FileStore.activeDocuments());
        }
    }

    private boolean uncheckedChanges = false;

    @Override
    public void doAsyncWork() {
        if (uncheckedChanges) {
            // Re-lint all active documents
            lint(FileStore.activeDocuments());
        }
    }

    private static final Logger LOG = Logger.getLogger("main");
}
