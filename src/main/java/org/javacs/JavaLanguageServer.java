package org.javacs;

import static org.javacs.JsonHelper.GSON;

import com.google.gson.*;
import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import javax.lang.model.element.*;
import org.javacs.action.CodeActionProvider;
import org.javacs.completion.CompletionProvider;
import org.javacs.completion.SignatureProvider;
import org.javacs.fold.FoldProvider;
import org.javacs.hover.HoverProvider;
import org.javacs.index.SymbolProvider;
import org.javacs.lens.CodeLensProvider;
import org.javacs.lsp.*;
import org.javacs.markup.ColorProvider;
import org.javacs.markup.ErrorProvider;
import org.javacs.navigation.DefinitionProvider;
import org.javacs.navigation.ReferenceProvider;
import org.javacs.rewrite.*;
import org.javacs.config.Config;
import org.javacs.config.IConfig;

class JavaLanguageServer extends LanguageServer {
    // TODO allow multiple workspace roots
    private Path workspaceRoot;
    private final LanguageClient client;
    private JavaCompilerService cacheCompiler;
    private JsonObject cacheSettings;
    private JsonObject settings = new JsonObject();
    private boolean modifiedBuild = true;

    JavaCompilerService compiler() {
        if (needsCompiler()) {
            cacheCompiler = createCompiler();
            cacheSettings = settings;
            modifiedBuild = false;
        }
        return cacheCompiler;
    }

    private boolean needsCompiler() {
        if (modifiedBuild) {
            return true;
        }
        if (!settings.equals(cacheSettings)) {
            LOG.info("Settings\n\t" + settings + "\nis different than\n\t" + cacheSettings);
            return true;
        }
        return false;
    }

    void lint(Collection<Path> files) {
        if (files.isEmpty()) return;
        LOG.info("Lint " + files.size() + " files...");
        var started = Instant.now();
        try (var task = compiler().compile(files.toArray(Path[]::new))) {
            var compiled = Instant.now();
            LOG.info("...compiled in " + Duration.between(started, compiled).toMillis() + " ms");
            for (var errs : new ErrorProvider(task).errors()) {
                client.publishDiagnostics(errs);
            }
            for (var colors : new ColorProvider(task).colors()) {
                client.customNotification("java/colors", GSON.toJsonTree(colors));
            }
            var published = Instant.now();
            LOG.info("...published in " + Duration.between(started, published).toMillis() + " ms");
        }
    }

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
        // Otherwise, combine inference with user-specified external dependencies
        } else {
          IConfig config;
          if (!settings.has("bazel")) {
            config = Config.buildConfig(workspaceRoot, externalDependencies);
          } else {
            config = Config.buildBazelConfig(workspaceRoot, settings.get("bazel").getAsString());
          }

          javaReportProgress(new JavaReportProgressParams("Inferring class path"));
          classPath = config.classpath();

          javaReportProgress(new JavaReportProgressParams("Inferring doc path"));
          var docPath = config.sourcepath();

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
        c.add("codeLensProvider", codeLensOptions);
        c.addProperty("foldingRangeProvider", true);
        c.addProperty("codeActionProvider", true);
        var renameOptions = new JsonObject();
        renameOptions.addProperty("prepareProvider", true);
        c.add("renameProvider", renameOptions);

        return new InitializeResult(c);
    }

    private static final String[] watchFiles = {
        "**/*.java", "**/pom.xml", "**/BUILD",
    };

    @Override
    public void initialized() {
        client.registerCapability("workspace/didChangeWatchedFiles", watchFiles(watchFiles));
    }

    private JsonObject watchFiles(String... globPatterns) {
        var options = new JsonObject();
        var watchers = new JsonArray();
        for (var p : globPatterns) {
            var config = new JsonObject();
            config.addProperty("globPattern", p);
            watchers.add(config);
        }
        options.add("watchers", watchers);
        return options;
    }

    @Override
    public void shutdown() {}

    public JavaLanguageServer(LanguageClient client) {
        this.client = client;
    }

    @Override
    public List<SymbolInformation> workspaceSymbols(WorkspaceSymbolParams params) {
        return new SymbolProvider(compiler()).findSymbols(params.query, 50);
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams change) {
        var java = change.settings.getAsJsonObject().get("java");
        LOG.info("Received java settings " + java);
        settings = java.getAsJsonObject();
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        for (var c : params.changes) {
            var file = Paths.get(c.uri);
            if (FileStore.isJavaFile(file)) {
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
                return;
            }
            var name = file.getFileName().toString();
            switch (name) {
                case "BUILD":
                case "pom.xml":
                    LOG.info("Compiler needs to be re-created because " + file + " has changed");
                    modifiedBuild = true;
            }
        }
    }

    @Override
    public Optional<CompletionList> completion(TextDocumentPositionParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) return Optional.empty();
        var file = Paths.get(params.textDocument.uri);
        var provider = new CompletionProvider(compiler());
        var list = provider.complete(file, params.position.line + 1, params.position.character + 1);
        if (list == CompletionProvider.NOT_SUPPORTED) return Optional.empty();
        return Optional.of(list);
    }

    @Override
    public CompletionItem resolveCompletionItem(CompletionItem unresolved) {
        new HoverProvider(compiler()).resolveCompletionItem(unresolved);
        return unresolved;
    }

    @Override
    public Optional<Hover> hover(TextDocumentPositionParams position) {
        var uri = position.textDocument.uri;
        var line = position.position.line + 1;
        var column = position.position.character + 1;
        if (!FileStore.isJavaFile(uri)) return Optional.empty();
        var file = Paths.get(uri);
        var list = new HoverProvider(compiler()).hover(file, line, column);
        if (list == HoverProvider.NOT_SUPPORTED) {
            return Optional.empty();
        }
        // TODO add range
        return Optional.of(new Hover(list));
    }

    @Override
    public Optional<SignatureHelp> signatureHelp(TextDocumentPositionParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) return Optional.empty();
        var file = Paths.get(params.textDocument.uri);
        var line = params.position.line + 1;
        var column = params.position.character + 1;
        var help = new SignatureProvider(compiler()).signatureHelp(file, line, column);
        if (help == SignatureProvider.NOT_SUPPORTED) return Optional.empty();
        return Optional.of(help);
    }

    @Override
    public Optional<List<Location>> gotoDefinition(TextDocumentPositionParams position) {
        if (!FileStore.isJavaFile(position.textDocument.uri)) return Optional.empty();
        var file = Paths.get(position.textDocument.uri);
        var line = position.position.line + 1;
        var column = position.position.character + 1;
        var found = new DefinitionProvider(compiler(), file, line, column).find();
        if (found == DefinitionProvider.NOT_SUPPORTED) {
            return Optional.empty();
        }
        return Optional.of(found);
    }

    @Override
    public Optional<List<Location>> findReferences(ReferenceParams position) {
        if (!FileStore.isJavaFile(position.textDocument.uri)) return Optional.empty();
        var file = Paths.get(position.textDocument.uri);
        var line = position.position.line + 1;
        var column = position.position.character + 1;
        var found = new ReferenceProvider(compiler(), file, line, column).find();
        if (found == ReferenceProvider.NOT_SUPPORTED) {
            return Optional.empty();
        }
        return Optional.of(found);
    }

    @Override
    public List<SymbolInformation> documentSymbol(DocumentSymbolParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) return List.of();
        var file = Paths.get(params.textDocument.uri);
        return new SymbolProvider(compiler()).documentSymbols(file);
    }

    @Override
    public List<CodeLens> codeLens(CodeLensParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) return List.of();
        var file = Paths.get(params.textDocument.uri);
        var task = compiler().parse(file);
        return CodeLensProvider.find(task);
    }

    @Override
    public CodeLens resolveCodeLens(CodeLens unresolved) {
        return null;
    }

    @Override
    public List<TextEdit> formatting(DocumentFormattingParams params) {
        var edits = new ArrayList<TextEdit>();
        var file = Paths.get(params.textDocument.uri);
        var fixImports = new AutoFixImports(file).rewrite(compiler()).get(file);
        Collections.addAll(edits, fixImports);
        var addOverrides = new AutoAddOverrides(file).rewrite(compiler()).get(file);
        Collections.addAll(edits, addOverrides);
        return edits;
    }

    @Override
    public List<FoldingRange> foldingRange(FoldingRangeParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) return List.of();
        var file = Paths.get(params.textDocument.uri);
        return new FoldProvider(compiler()).foldingRanges(file);
    }

    @Override
    public Optional<RenameResponse> prepareRename(TextDocumentPositionParams params) {
        if (!FileStore.isJavaFile(params.textDocument.uri)) return Optional.empty();
        LOG.info("Try to rename...");
        var file = Paths.get(params.textDocument.uri);
        try (var task = compiler().compile(file)) {
            var lines = task.root().getLineMap();
            var cursor = lines.getPosition(params.position.line + 1, params.position.character + 1);
            var path = new FindNameAt(task).scan(task.root(), cursor);
            if (path == null) {
                LOG.info("...no element under cursor");
                return Optional.empty();
            }
            var el = Trees.instance(task.task).getElement(path);
            if (el == null) {
                LOG.info("...couldn't resolve element");
                return Optional.empty();
            }
            if (!canRename(el)) {
                LOG.info("...can't rename " + el);
                return Optional.empty();
            }
            if (!canFindSource(el)) {
                LOG.info("...can't find source for " + el);
                return Optional.empty();
            }
            var response = new RenameResponse();
            response.range = FindHelper.location(task, path).range;
            response.placeholder = el.getSimpleName().toString();
            return Optional.of(response);
        }
    }

    private boolean canRename(Element rename) {
        switch (rename.getKind()) {
            case METHOD:
            case FIELD:
            case LOCAL_VARIABLE:
            case PARAMETER:
            case EXCEPTION_PARAMETER:
                return true;
            default:
                // TODO rename other types
                return false;
        }
    }

    private boolean canFindSource(Element rename) {
        if (rename == null) return false;
        if (rename instanceof TypeElement) {
            var type = (TypeElement) rename;
            var name = type.getQualifiedName().toString();
            return compiler().findTypeDeclaration(name) != CompilerProvider.NOT_FOUND;
        }
        return canFindSource(rename.getEnclosingElement());
    }

    @Override
    public WorkspaceEdit rename(RenameParams params) {
        var rw = createRewrite(params);
        var response = new WorkspaceEdit();
        var map = rw.rewrite(compiler());
        for (var editedFile : map.keySet()) {
            response.changes.put(editedFile.toUri(), List.of(map.get(editedFile)));
        }
        return response;
    }

    private Rewrite createRewrite(RenameParams params) {
        var file = Paths.get(params.textDocument.uri);
        try (var task = compiler().compile(file)) {
            var lines = task.root().getLineMap();
            var position = lines.getPosition(params.position.line + 1, params.position.character + 1);
            var path = new FindNameAt(task).scan(task.root(), position);
            if (path == null) return Rewrite.NOT_SUPPORTED;
            var el = Trees.instance(task.task).getElement(path);
            switch (el.getKind()) {
                case METHOD:
                    return renameMethod(task, (ExecutableElement) el, params.newName);
                case FIELD:
                    return renameField(task, (VariableElement) el, params.newName);
                case LOCAL_VARIABLE:
                case PARAMETER:
                case EXCEPTION_PARAMETER:
                    return renameVariable(task, (VariableElement) el, params.newName);
                default:
                    return Rewrite.NOT_SUPPORTED;
            }
        }
    }

    private RenameMethod renameMethod(CompileTask task, ExecutableElement method, String newName) {
        var parent = (TypeElement) method.getEnclosingElement();
        var className = parent.getQualifiedName().toString();
        var methodName = method.getSimpleName().toString();
        var erasedParameterTypes = new String[method.getParameters().size()];
        for (var i = 0; i < erasedParameterTypes.length; i++) {
            var type = method.getParameters().get(i).asType();
            erasedParameterTypes[i] = task.task.getTypes().erasure(type).toString();
        }
        return new RenameMethod(className, methodName, erasedParameterTypes, newName);
    }

    private RenameField renameField(CompileTask task, VariableElement field, String newName) {
        var parent = (TypeElement) field.getEnclosingElement();
        var className = parent.getQualifiedName().toString();
        var fieldName = field.getSimpleName().toString();
        return new RenameField(className, fieldName, newName);
    }

    private RenameVariable renameVariable(CompileTask task, VariableElement variable, String newName) {
        var trees = Trees.instance(task.task);
        var path = trees.getPath(variable);
        var file = Paths.get(path.getCompilationUnit().getSourceFile().toUri());
        var position = trees.getSourcePositions().getStartPosition(path.getCompilationUnit(), path.getLeaf());
        return new RenameVariable(file, (int) position, newName);
    }

    private boolean uncheckedChanges = false;
    private Path lastEdited = Paths.get("");

    @Override
    public void didOpenTextDocument(DidOpenTextDocumentParams params) {
        FileStore.open(params);
        if (!FileStore.isJavaFile(params.textDocument.uri)) return;
        lastEdited = Paths.get(params.textDocument.uri);
        uncheckedChanges = true;
    }

    @Override
    public void didChangeTextDocument(DidChangeTextDocumentParams params) {
        FileStore.change(params);
        lastEdited = Paths.get(params.textDocument.uri);
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
    public List<CodeAction> codeAction(CodeActionParams params) {
        var provider = new CodeActionProvider(compiler());
        if (params.context.diagnostics.isEmpty()) {
            return provider.codeActionsForCursor(params);
        } else {
            return provider.codeActionForDiagnostics(params);
        }
    }

    @Override
    public void didSaveTextDocument(DidSaveTextDocumentParams params) {
        if (FileStore.isJavaFile(params.textDocument.uri)) {
            // Re-lint all active documents
            lint(FileStore.activeDocuments());
        }
    }

    @Override
    public void doAsyncWork() {
        if (uncheckedChanges && FileStore.activeDocuments().contains(lastEdited)) {
            lint(List.of(lastEdited));
            uncheckedChanges = false;
        }
    }

    private static final Logger LOG = Logger.getLogger("main");
}
