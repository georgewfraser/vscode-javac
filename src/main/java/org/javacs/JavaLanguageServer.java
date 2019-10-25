package org.javacs;

import com.google.gson.*;
import com.sun.source.tree.*;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;
import java.util.logging.Logger;
import javax.lang.model.element.*;
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

    private final Map<Path, CachedLint> lintCache = new HashMap<>();

    void lint(Collection<Path> files) {
        LOG.info("Lint " + files.size() + " files...");
        var started = Instant.now();
        if (files.isEmpty()) return;
        // Update cache
        lintCache.keySet().retainAll(files);
        for (var file : files) {
            if (!lintCache.containsKey(file)) {
                lintCache.put(file, new CachedLint(file));
            }
        }
        // Construct todo list, using fast lint when possible
        var sources = new ArrayList<SourceFileObject>();
        for (var file : files) {
            var cached = lintCache.get(file);
            var span = cached.edited();
            if (span == Span.INVALID) {
                LOG.info(String.format("...re-lint all of %s", file.getFileName()));
                var source = new SourceFileObject(file);
                sources.add(source);
            } else if (span == Span.EMPTY) {
                LOG.info(String.format("...skip linting %s", file.getFileName()));
            } else {
                LOG.info(String.format("...re-lint %s %d-%d", file.getFileName(), span.start, span.until));
                var contents = cached.pruneNewContents(span);
                var source = new SourceFileObject(file, contents, Instant.now());
                sources.add(source);
            }
        }
        if (sources.isEmpty()) {
            LOG.info("...nothing has changed, skipping lint");
            return;
        }
        // Compile mixed list
        try (var batch = compiler().compileBatch(sources)) {
            var compiled = Instant.now();
            var elapsed = Duration.between(started, compiled).toMillis();
            LOG.info(String.format("...compiled in %d ms", elapsed));
            // Update cache and publish
            var errors = batch.reportErrors();
            var colors = batch.colors();
            for (var source : sources) {
                var cached = lintCache.get(source.path);
                var span = cached.edited();
                cached.update(span, errors.get(source.path), colors.get(source.path));
                client.publishDiagnostics(cached.lspDiagnostics());
                client.customNotification("java/colors", GSON.toJsonTree(cached.lspColors()));
            }
            // Done
            uncheckedChanges = false;
        }
        // Log timing
        var done = Instant.now();
        var elapsed = Duration.between(started, done).toMillis();
        LOG.info(String.format("...linted in %d ms", elapsed));
    }

    static final Gson GSON = new GsonBuilder().registerTypeAdapter(Ptr.class, new PtrAdapter()).create();

    private void javaStartProgress(JavaStartProgressParams params) {
        client.customNotification("java/startProgress", GSON.toJsonTree(params));
    }

    private void javaReportProgress(JavaReportProgressParams params) {
        client.customNotification("java/reportProgress", GSON.toJsonTree(params));
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
        client.registerCapability("workspace/didChangeWatchedFiles", GSON.toJsonTree(options));
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
        var java = change.settings.getAsJsonObject().get("java");
        LOG.info("Received java settings " + java);
        settings = java.getAsJsonObject();
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

    static int isMemberSelect(String contents, int cursor) {
        // Start at char before cursor
        cursor--;
        // Move back until we find a non-identifier char
        while (cursor > 0 && Character.isJavaIdentifierPart(contents.charAt(cursor))) {
            cursor--;
        }
        if (cursor <= 0 || contents.charAt(cursor) != '.') {
            return -1;
        }
        // Move cursor back until we find a non-whitespace char
        while (cursor > 0 && Character.isWhitespace(contents.charAt(cursor - 1))) {
            cursor--;
        }
        return cursor;
    }

    static int isMemberReference(String contents, int cursor) {
        // Start at char before cursor
        cursor--;
        // Move back until we find a non-identifier char
        while (cursor > 1 && Character.isJavaIdentifierPart(contents.charAt(cursor))) {
            cursor--;
        }
        if (!contents.startsWith("::", cursor - 1)) {
            return -1;
        }
        // Skip first : in ::
        cursor--;
        // Move cursor back until we find a non-whitespace char
        while (cursor > 0 && Character.isWhitespace(contents.charAt(cursor - 1))) {
            cursor--;
        }
        return cursor;
    }

    private static boolean isQualifiedIdentifierPart(char c) {
        return Character.isJavaIdentifierPart(c) || c == '.';
    }

    static int isPartialAnnotation(String contents, int cursor) {
        // Start at char before cursor
        cursor--;
        // Move back until we find a non-identifier char
        while (cursor > 0 && isQualifiedIdentifierPart(contents.charAt(cursor))) {
            cursor--;
        }
        if (cursor >= 0 && contents.charAt(cursor) == '@') {
            return cursor;
        } else {
            return -1;
        }
    }

    static boolean isPartialCase(String contents, int cursor) {
        // Start at char before cursor
        cursor--;
        // Move back until we find a non-identifier char
        while (cursor > 0 && Character.isJavaIdentifierPart(contents.charAt(cursor))) {
            cursor--;
        }
        // Skip space
        while (cursor > 0 && Character.isWhitespace(contents.charAt(cursor))) {
            cursor--;
        }
        return contents.startsWith("case", cursor - 3);
    }

    static String partialName(String contents, int cursor) {
        // Start at char before cursor
        var start = cursor - 1;
        // Move back until we find a non-identifier char
        while (start >= 0 && Character.isJavaIdentifierPart(contents.charAt(start))) {
            start--;
        }
        return contents.substring(start + 1, cursor);
    }

    private static String restOfLine(String contents, int cursor) {
        var endOfLine = contents.indexOf('\n', cursor);
        if (endOfLine == -1) {
            return contents.substring(cursor);
        }
        return contents.substring(cursor, endOfLine);
    }

    private static boolean hasParen(String contents, int cursor) {
        return cursor < contents.length() && contents.charAt(cursor) == '(';
    }

    private static String eraseRegion(String contents, long start, long end) {
        var buffer = new StringBuffer(contents);
        for (int i = (int) start; i < end; i++) {
            switch (buffer.charAt(i)) {
                case '\r':
                case '\n':
                    break;
                default:
                    buffer.setCharAt(i, ' ');
            }
        }
        return buffer.toString();
    }

    @Override
    public Optional<CompletionList> completion(TextDocumentPositionParams position) {
        var started = Instant.now();
        var uri = position.textDocument.uri;
        if (!FileStore.isJavaFile(uri)) return Optional.empty();
        var file = Paths.get(uri);
        var line = position.position.line + 1;
        var column = position.position.character + 1;
        LOG.info(String.format("Complete at %s(%d,%d)...", file, line, column));
        // Figure out what kind of completion we want to do
        var contents = FileStore.contents(file);
        var cursor = FileStore.offset(contents, line, column);
        var addParens = !hasParen(contents, cursor);
        var addSemi = restOfLine(contents, cursor).matches("\\s*");
        // Complete object. or object.partial
        var dot = isMemberSelect(contents, cursor);
        if (dot != -1) {
            LOG.info("...complete members");
            // Erase .partial
            // contents = eraseRegion(contents, dot, cursor);
            var parse = Parser.parseJavaFileObject(new SourceFileObject(file, contents, Instant.now()));
            contents = parse.prune(dot);
            try (var compile = compiler().compileBatch(List.of(new SourceFileObject(file, contents, Instant.now())))) {
                var list = compile.completeMembers(file, dot, addParens, addSemi);
                logCompletionTiming(started, list, false);
                return Optional.of(new CompletionList(false, list));
            }
        }
        // Complete object:: or object::partial
        var ref = isMemberReference(contents, cursor);
        if (ref != -1) {
            LOG.info("...complete references");
            // Erase ::partial
            // contents = eraseRegion(contents, ref, cursor);
            var parse = Parser.parseJavaFileObject(new SourceFileObject(file, contents, Instant.now()));
            contents = parse.prune(ref);
            try (var compile = compiler().compileBatch(List.of(new SourceFileObject(file, contents, Instant.now())))) {
                var list = compile.completeReferences(file, ref);
                logCompletionTiming(started, list, false);
                return Optional.of(new CompletionList(false, list));
            }
        }
        // Complete @Partial
        var at = isPartialAnnotation(contents, cursor);
        if (at != -1) {
            LOG.info("...complete annotations");
            var partialName = contents.substring(at + 1, cursor);
            var parse = Parser.parseJavaFileObject(new SourceFileObject(file, contents, Instant.now()));
            contents = parse.prune(cursor);
            try (var compile = compiler().compileBatch(List.of(new SourceFileObject(file, contents, Instant.now())))) {
                var list = compile.completeAnnotations(file, cursor, partialName);
                var isIncomplete = list.size() >= CompileBatch.MAX_COMPLETION_ITEMS;
                logCompletionTiming(started, list, isIncomplete);
                return Optional.of(new CompletionList(isIncomplete, list));
            }
        }
        // Complete case partial
        if (isPartialCase(contents, cursor)) {
            LOG.info("...complete members");
            var parse = Parser.parseJavaFileObject(new SourceFileObject(file, contents, Instant.now()));
            contents = parse.eraseCase(cursor);
            parse = Parser.parseJavaFileObject(new SourceFileObject(file, contents, Instant.now()));
            contents = parse.prune(cursor);
            try (var compile = compiler().compileBatch(List.of(new SourceFileObject(file, contents, Instant.now())))) {
                var list = compile.completeCases(file, cursor);
                logCompletionTiming(started, list, false);
                return Optional.of(new CompletionList(false, list));
            }
        }
        // Complete partial
        var looksLikeIdentifier = Character.isJavaIdentifierPart(contents.charAt(cursor - 1));
        if (looksLikeIdentifier) {
            var parse = Parser.parseJavaFileObject(new SourceFileObject(file, contents, Instant.now()));
            if (parse.isIdentifier(cursor)) {
                LOG.info("...complete identifiers");
                contents = parse.prune(cursor);
                parse = Parser.parseJavaFileObject(new SourceFileObject(file, contents, Instant.now()));
                var path = parse.findPath(cursor);
                try (var compile =
                        compiler().compileBatch(List.of(new SourceFileObject(file, contents, Instant.now())))) {
                    var list =
                            compile.completeIdentifiers(
                                    file,
                                    cursor,
                                    Parser.inClass(path),
                                    Parser.inMethod(path),
                                    partialName(contents, cursor),
                                    addParens,
                                    addSemi);
                    var isIncomplete = list.size() >= CompileBatch.MAX_COMPLETION_ITEMS;
                    logCompletionTiming(started, list, isIncomplete);
                    return Optional.of(new CompletionList(isIncomplete, list));
                }
            }
        }
        LOG.info("...complete keywords");
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

    private void logCompletionTiming(Instant started, List<?> list, boolean isIncomplete) {
        var elapsedMs = Duration.between(started, Instant.now()).toMillis();
        if (isIncomplete) LOG.info(String.format("Found %d items (incomplete) in %,d ms", list.size(), elapsedMs));
        else LOG.info(String.format("...found %d items in %,d ms", list.size(), elapsedMs));
    }

    private Optional<MarkupContent> findDocs(Ptr ptr) {
        LOG.info(String.format("Find docs for `%s`...", ptr));

        // Find el in the doc path
        var file = compiler().docs().find(ptr);
        if (!file.isPresent()) return Optional.empty();
        // Parse file and find el
        var parse = Parser.parseJavaFileObject(file.get());
        var path = parse.fuzzyFind(ptr);
        if (!path.isPresent()) return Optional.empty();
        // Parse the doctree associated with el
        var docTree = parse.doc(path.get());
        var string = Parser.asMarkupContent(docTree);
        return Optional.of(string);
    }

    private Optional<String> findMethodDetails(Ptr ptr) {
        LOG.info(String.format("Find details for method `%s`...", ptr));

        // TODO find and parse happens twice
        // Find method in the doc path
        var file = compiler().docs().find(ptr);
        if (!file.isPresent()) return Optional.empty();
        // Parse file and find method
        var parse = Parser.parseJavaFileObject(file.get());
        var path = parse.fuzzyFind(ptr);
        if (!path.isPresent()) return Optional.empty();
        // Should be a MethodTree
        var tree = path.get().getLeaf();
        if (!(tree instanceof MethodTree)) {
            LOG.warning(String.format("...method `%s` associated with non-method tree `%s`", ptr, tree));
            return Optional.empty();
        }
        // Write description of method using info from source
        var methodTree = (MethodTree) tree;
        var args = new StringJoiner(", ");
        for (var p : methodTree.getParameters()) {
            args.add(p.getType() + " " + p.getName());
        }
        var details = String.format("%s %s(%s)", methodTree.getReturnType(), methodTree.getName(), args);
        return Optional.of(details);
    }

    @Override
    public CompletionItem resolveCompletionItem(CompletionItem unresolved) {
        if (unresolved.data == null) return unresolved;
        var data = GSON.fromJson(unresolved.data, CompletionData.class);
        var markdown = findDocs(data.ptr);
        if (markdown.isPresent()) {
            unresolved.documentation = markdown.get();
        }
        if (data.ptr.isMethod()) {
            var details = findMethodDetails(data.ptr);
            if (details.isPresent()) {
                unresolved.detail = details.get();
                if (data.plusOverloads != 0) {
                    unresolved.detail += " (+" + data.plusOverloads + " overloads)";
                }
            }
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
        result.append(" ").append(ShortTypePrinter.DEFAULT.print(t.asType()));
        var superType = ShortTypePrinter.DEFAULT.print(t.getSuperclass());
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
            return ShortTypePrinter.DEFAULT.printMethod(m);
        } else if (e instanceof VariableElement) {
            var v = (VariableElement) e;
            return ShortTypePrinter.DEFAULT.print(v.asType()) + " " + v;
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
        var parse = Parser.parseJavaFileObject(file.get());
        var path = parse.fuzzyFind(ptr);
        if (!path.isPresent()) return Optional.empty();
        var doc = parse.doc(path.get());
        var md = Parser.asMarkdown(doc);
        return Optional.of(md);
    }

    @Override
    public Optional<Hover> hover(TextDocumentPositionParams position) {
        var uri = position.textDocument.uri;
        var line = position.position.line + 1;
        var column = position.position.character + 1;
        if (!FileStore.isJavaFile(uri)) return Optional.empty();
        var file = Paths.get(uri);
        // Log start time
        LOG.info(String.format("Hover over %s(%d,%d) ...", uri.getPath(), line, column));
        var started = Instant.now();
        // Compile entire file
        var sources = Set.of(new SourceFileObject(file));
        try (var compile = compiler().compileBatch(sources)) {
            // Find element under cursor
            var el = compile.element(file, line, column);
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

    @Override
    public Optional<SignatureHelp> signatureHelp(TextDocumentPositionParams position) {
        var uri = position.textDocument.uri;
        if (!FileStore.isJavaFile(uri)) return Optional.empty();
        var file = Paths.get(uri);
        var line = position.position.line + 1;
        var column = position.position.character + 1;
        LOG.info(String.format("Find signature at at %s(%d,%d)...", file, line, column));
        var contents = FileStore.contents(file);
        var cursor = FileStore.offset(contents, line, column);
        var parse = Parser.parseJavaFileObject(new SourceFileObject(file, contents, Instant.now()));
        contents = parse.prune(cursor);
        try (var compile = compiler().compileBatch(List.of(new SourceFileObject(file, contents, Instant.now())))) {
            return compile.signatureHelp(file, cursor);
        }
    }

    @Override
    public Optional<List<Location>> gotoDefinition(TextDocumentPositionParams position) {
        var fromUri = position.textDocument.uri;
        if (!FileStore.isJavaFile(fromUri)) return Optional.empty();
        var fromFile = Paths.get(fromUri);
        var fromLine = position.position.line + 1;
        var fromColumn = position.position.character + 1;

        // Compile from-file and identify element under cursor
        LOG.info(String.format("Go-to-def at %s:%d...", fromUri, fromLine));
        Optional<Element> toEl;
        var sources = Set.of(new SourceFileObject(fromFile));
        try (var compile = compiler().compileBatch(sources)) {
            toEl = compile.element(fromFile, fromLine, fromColumn);
            if (!toEl.isPresent()) {
                LOG.info(String.format("...no element at cursor"));
                return Optional.empty();
            }
        }

        // Compile all files that *might* contain definitions of fromEl
        var toFiles = Parser.potentialDefinitions(toEl.get());
        toFiles.add(fromFile);
        var eraseCode = pruneWord(toFiles, Parser.simpleName(toEl.get()));
        try (var batch = compiler().compileBatch(eraseCode)) {
            // Find fromEl again, so that we have an Element from the current batch
            var fromElAgain = batch.element(fromFile, fromLine, fromColumn).get();
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
        var toFile = Paths.get(toUri);
        var toLine = position.position.line + 1;
        var toColumn = position.position.character + 1;

        // TODO use parser to figure out batch to compile, avoiding compiling twice

        // Compile from-file and identify element under cursor
        LOG.warning(String.format("Looking for references to %s(%d,%d)...", toUri.getPath(), toLine, toColumn));
        Optional<Element> toEl;
        var sources = Set.of(new SourceFileObject(toFile));
        try (var compile = compiler().compileBatch(sources)) {
            toEl = compile.element(toFile, toLine, toColumn);
            if (!toEl.isPresent()) {
                LOG.warning("...no element under cursor");
                return Optional.empty();
            }
        }

        // Compile all files that *might* contain references to toEl
        var name = Parser.simpleName(toEl.get());
        var fromFiles = new HashSet<Path>();
        var isLocal =
                toEl.get() instanceof VariableElement && !(toEl.get().getEnclosingElement() instanceof TypeElement);
        if (!isLocal) {
            var isType = false;
            switch (toEl.get().getKind()) {
                case ANNOTATION_TYPE:
                case CLASS:
                case INTERFACE:
                    isType = true;
            }
            var flags = toEl.get().getModifiers();
            var possible = Parser.potentialReferences(toFile, name, isType, flags);
            fromFiles.addAll(possible);
        }
        fromFiles.add(toFile);
        var eraseCode = pruneWord(fromFiles, name);
        try (var batch = compiler().compileBatch(eraseCode)) {
            var fromTreePaths = batch.references(toFile, toLine, toColumn);
            LOG.info(String.format("...found %d references", fromTreePaths.map(List::size).orElse(0)));
            if (!fromTreePaths.isPresent()) return Optional.empty();
            var result = new ArrayList<Location>();
            for (var path : fromTreePaths.get()) {
                var fromUri = path.getCompilationUnit().getSourceFile().toUri();
                var fromRange = batch.range(path);
                if (!fromRange.isPresent()) {
                    LOG.warning(String.format("...couldn't locate `%s`", path.getLeaf()));
                    continue;
                }
                var from = new Location(fromUri, fromRange.get());
                result.add(from);
            }
            return Optional.of(result);
        }
    }

    private List<JavaFileObject> pruneWord(Collection<Path> files, String name) {
        LOG.info(String.format("...prune code that doesn't contain `%s`", name));
        var sources = new ArrayList<JavaFileObject>();
        for (var f : files) {
            var pruned = Parser.parseFile(f).prune(name);
            sources.add(new SourceFileObject(f, pruned, Instant.EPOCH));
        }
        return sources;
    }

    private Parser cacheParse;
    private Path cacheParseFile = Paths.get("/NONE");
    private int cacheParseVersion = -1;

    private void updateCachedParse(Path file) {
        if (file.equals(cacheParseFile) && FileStore.version(file) == cacheParseVersion) return;
        cacheParse = Parser.parseFile(file);
        cacheParseFile = file;
        cacheParseVersion = FileStore.version(file);
    }

    @Override
    public List<SymbolInformation> documentSymbol(DocumentSymbolParams params) {
        var uri = params.textDocument.uri;
        if (!FileStore.isJavaFile(uri)) return List.of();
        var file = Paths.get(uri);
        updateCachedParse(file);
        var infos = cacheParse.documentSymbols();
        return infos;
    }

    @Override
    public List<CodeLens> codeLens(CodeLensParams params) {
        var uri = params.textDocument.uri;
        if (!FileStore.isJavaFile(uri)) return List.of();
        var file = Paths.get(uri);
        updateCachedParse(file);
        var declarations = cacheParse.codeLensDeclarations();
        var result = new ArrayList<CodeLens>();
        for (var d : declarations) {
            var range = cacheParse.range(d);
            if (!range.isPresent()) continue;
            var className = Parser.className(d);
            var memberName = Parser.memberName(d);
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
                if (!memberName.isEmpty()) arguments.add(memberName);
                else arguments.add(JsonNull.INSTANCE);
                // 'Run Test' code lens
                var command = new Command("Run Test", "java.command.test.run", arguments);
                var lens = new CodeLens(range.get(), command, null);
                result.add(lens);
                // 'Debug Test' code lens
                // TODO this could be a CPU hot spot
                var sourceRoots = new JsonArray();
                for (var path : FileStore.sourceRoots()) {
                    sourceRoots.add(path.toString());
                }
                arguments.add(sourceRoots);
                command = new Command("Debug Test", "java.command.test.debug", arguments);
                lens = new CodeLens(range.get(), command, null);
                result.add(lens);
            }
            if (cacheParse.showReferencesCodeLens(d)) {
                // Unresolved "_ references" code lens
                var t = d.getLeaf();
                var start = range.get().start;
                var line = start.line;
                var character = start.character;
                var data = new CodeLensData();
                data.uri = uri;
                data.line = line;
                data.character = character;
                data.name = memberName.isEmpty() ? className : memberName;
                data.signature = Parser.signature(d);
                data.kind = t.getKind();
                if (t instanceof MethodTree) {
                    var method = (MethodTree) t;
                    data.flags = method.getModifiers().getFlags();
                } else if (t instanceof ClassTree) {
                    var type = (ClassTree) t;
                    data.flags = type.getModifiers().getFlags();
                } else if (t instanceof VariableTree) {
                    var field = (VariableTree) t;
                    data.flags = field.getModifiers().getFlags();
                }
                var lens = new CodeLens(range.get(), null, GSON.toJsonTree(data));
                result.add(lens);
            }
        }
        return result;
    }

    @Override
    public CodeLens resolveCodeLens(CodeLens unresolved) {
        var data = GSON.fromJson(unresolved.data, CodeLensData.class);
        LOG.info(String.format("Count references to `%s`...", data.name));
        var self = countSelfReferences(data);
        var cross = countCrossReferences(data);
        var count = self + cross;
        String title;
        if (count == -1) title = "? references";
        else if (count == 1) title = "1 reference";
        else if (cross == TOO_EXPENSIVE) title = "Find references";
        else title = String.format("%d references", count);
        var command = "java.command.findReferences";
        var arguments = new JsonArray();
        arguments.add(data.uri.toString());
        arguments.add(data.line);
        arguments.add(data.character);
        unresolved.command = new Command(title, command, arguments);

        return unresolved;
    }

    private Map<String, Integer> cacheSelfReferences = new HashMap<>();
    private Path cacheSelfReferencesFile = Paths.get("/NONE");
    private int cacheSelfReferencesVersion = -1;

    private int countSelfReferences(CodeLensData data) {
        var file = Paths.get(data.uri);
        if (!cacheSelfReferencesFile.equals(file) || cacheSelfReferencesVersion < FileStore.version(file)) {
            updateCacheSelfReferences(file);
        }
        return cacheSelfReferences.get(data.signature);
    }

    private void updateCacheSelfReferences(Path file) {
        LOG.info(String.format("...count all self-references in %s...", file));
        cacheSelfReferences.clear();
        updateCachedParse(file);
        var sources = Set.of(new SourceFileObject(file));
        try (var batch = compiler().compileBatch(sources)) {
            for (var d : cacheParse.codeLensDeclarations()) {
                if (!cacheParse.showReferencesCodeLens(d)) continue;
                var range = cacheParse.range(d);
                if (range.isEmpty()) continue;
                var start = range.get().start;
                var fromPaths = batch.references(file, start.line + 1, start.character + 1).orElse(List.of());
                var signature = Parser.signature(d);
                cacheSelfReferences.put(signature, fromPaths.size());
            }
        }
        cacheSelfReferencesFile = file;
        cacheSelfReferencesVersion = FileStore.version(file);
    }

    /** Cache reference counts on Parser.signature(_) */
    private Cache<String, Integer> cacheCountCrossReferences = new Cache<>();

    private static final int TOO_EXPENSIVE = 100;

    private int countCrossReferences(CodeLensData data) {
        LOG.info(String.format("...count cross-file references to `%s`...", data.name));
        // Identify all files that *might* contain references to toEl
        var toFile = Paths.get(data.uri);
        var fromFiles = Parser.potentialReferences(toFile, data.name, isType(data.kind), data.flags);
        // If it's too expensive to compute the code lens
        if (fromFiles.size() > 100) {
            LOG.info(String.format("...counting %d files is too expensive", fromFiles.size()));
            return TOO_EXPENSIVE;
        }
        // Figure out what files need to be updated
        var todo = new HashSet<Path>();
        for (var fromFile : fromFiles) {
            if (cacheCountCrossReferences.needs(fromFile, data.signature)) {
                todo.add(fromFile);
            }
        }
        // Update the cache
        if (!todo.isEmpty()) {
            todo.add(toFile);
            LOG.info(String.format("...compile %d files", todo.size()));
            var eraseCode = pruneWord(todo, data.name);
            var countByFile = new HashMap<Path, Integer>();
            try (var batch = compiler().compileBatch(eraseCode)) {
                var fromPaths = batch.references(toFile, data.line + 1, data.character + 1).orElse(List.of());
                for (var fromPath : fromPaths) {
                    var fromFile = Paths.get(fromPath.getCompilationUnit().getSourceFile().toUri());
                    var newCount = countByFile.getOrDefault(fromFile, 0) + 1;
                    countByFile.put(fromFile, newCount);
                }
            }
            for (var fromFile : todo) {
                var count = countByFile.getOrDefault(fromFile, 0);
                // TODO consider not caching if fromUri contains errors
                cacheCountCrossReferences.load(fromFile, data.signature, count);
            }
            LOG.info(String.format("...found references in %d files", countByFile.size()));
        }
        // Sum up the count
        var count = 0;
        for (var fromFile : fromFiles) {
            if (fromFile.equals(toFile)) continue;
            count += cacheCountCrossReferences.get(fromFile, data.signature);
        }
        return count;
    }

    private boolean isType(Tree.Kind kind) {
        switch (kind) {
            case ANNOTATION_TYPE:
            case CLASS:
            case INTERFACE:
                return true;
            default:
                return false;
        }
    }

    @Override
    public List<TextEdit> formatting(DocumentFormattingParams params) {
        var file = Paths.get(params.textDocument.uri);
        var sources = Set.of(new SourceFileObject(file));
        try (var compile = compiler().compileBatch(sources)) {
            var edits = new ArrayList<TextEdit>();
            edits.addAll(fixImports(compile, file));
            edits.addAll(addOverrides(compile, file));
            // TODO replace var with type name when vars are copy-pasted into fields
            // TODO replace ThisClass.staticMethod() with staticMethod() when ThisClass is useless
            return edits;
        }
    }

    private List<TextEdit> fixImports(CompileBatch compile, Path file) {
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
            if (i != null) insertText.append("import ").append(i).append(";");
            insertText.append("\n");
        }
        var insertPosition = new Position((int) insertLine, 0);
        var insert = new TextEdit(new Range(insertPosition, insertPosition), insertText.toString());
        edits.add(insert);

        return edits;
    }

    private List<TextEdit> addOverrides(CompileBatch compile, Path file) {
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
        if (!FileStore.isJavaFile(params.textDocument.uri)) return List.of();
        var file = Paths.get(params.textDocument.uri);
        updateCachedParse(file);
        return cacheParse.foldingRanges();
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
        if (!FileStore.isJavaFile(params.textDocument.uri)) return;
        // So that subsequent documentSymbol and codeLens requests will be faster
        var file = Paths.get(params.textDocument.uri);
        updateCachedParse(file);
        uncheckedChanges = true;
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
            client.publishDiagnostics(new PublishDiagnosticsParams(params.textDocument.uri, List.of()));
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

class CompletionData {
    public Ptr ptr;
    public int plusOverloads;
}

class CodeLensData {
    URI uri;
    int line, character;
    String name, signature;
    Tree.Kind kind;
    Set<Modifier> flags;
}
