package org.javacs;

import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.code.Symbol;
import io.typefox.lsapi.services.*;
import io.typefox.lsapi.*;
import io.typefox.lsapi.impl.*;

import javax.tools.*;
import java.io.*;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.javacs.Main.JSON;

class JavaLanguageServer implements LanguageServer {
    private static final Logger LOG = Logger.getLogger("main");
    private Path workspaceRoot;
    private Consumer<PublishDiagnosticsParams> publishDiagnostics = p -> {};
    private Consumer<MessageParams> showMessage = m -> {};
    private Map<Path, String> sourceByPath = new HashMap<>();

    public JavaLanguageServer() {
        this.testJavac = Optional.empty();
    }

    public JavaLanguageServer(JavacHolder testJavac) {
        this.testJavac = Optional.of(testJavac);
    }

    public void onError(String message, Throwable error) {
        if (error instanceof ShowMessageException)
            showMessage.accept(((ShowMessageException) error).message);
        else if (error instanceof NoJavaConfigException) {
            // Swallow error
            // If you want to show a message for no-java-config, 
            // you have to specifically catch the error lower down and re-throw it
            LOG.warning(error.getMessage());
        }
        else {
            LOG.log(Level.SEVERE, message, error);

            MessageParamsImpl m = new MessageParamsImpl();

            m.setMessage(message);
            m.setType(MessageType.Error);

            showMessage.accept(m);
        }
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        workspaceRoot = Paths.get(params.getRootPath()).toAbsolutePath().normalize();

        InitializeResultImpl result = new InitializeResultImpl();

        ServerCapabilitiesImpl c = new ServerCapabilitiesImpl();

        c.setTextDocumentSync(TextDocumentSyncKind.Incremental);
        c.setDefinitionProvider(true);
        c.setCompletionProvider(new CompletionOptionsImpl());
        c.setHoverProvider(true);
        c.setWorkspaceSymbolProvider(true);
        c.setReferencesProvider(true);
        c.setDocumentSymbolProvider(true);

        result.setCapabilities(c);

//        LanguageDescriptionImpl java = new LanguageDescriptionImpl();
//
//        java.setLanguageId("java");
//        java.setFileExtensions(Collections.singletonList(".java"));
//
//        result.setSupportedLanguages(Collections.singletonList(java));

        return CompletableFuture.completedFuture(result);
    }

    @Override
    public void shutdown() {

    }

    @Override
    public void exit() {

    }

    @Override
    public void onTelemetryEvent(Consumer<Object> consumer) {
        // Nothing to do
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return new TextDocumentService() {
            @Override
            public CompletableFuture<CompletionList> completion(TextDocumentPositionParams position) {
                return CompletableFuture.completedFuture(autocomplete(position));
            }

            @Override
            public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem unresolved) {
                return null;
            }

            @Override
            public CompletableFuture<Hover> hover(TextDocumentPositionParams position) {
                return CompletableFuture.completedFuture(doHover(position));
            }

            @Override
            public CompletableFuture<SignatureHelp> signatureHelp(TextDocumentPositionParams position) {
                return null;
            }

            @Override
            public CompletableFuture<List<? extends Location>> definition(TextDocumentPositionParams position) {
                return CompletableFuture.completedFuture(gotoDefinition(position));
            }

            @Override
            public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
                return CompletableFuture.completedFuture(findReferences(params));
            }

            @Override
            public CompletableFuture<DocumentHighlight> documentHighlight(TextDocumentPositionParams position) {
                return null;
            }

            @Override
            public CompletableFuture<List<? extends SymbolInformation>> documentSymbol(DocumentSymbolParams params) {
                return CompletableFuture.completedFuture(findDocumentSymbols(params));
            }

            @Override
            public CompletableFuture<List<? extends Command>> codeAction(CodeActionParams params) {
                return null;
            }

            @Override
            public CompletableFuture<List<? extends CodeLens>> codeLens(CodeLensParams params) {
                return null;
            }

            @Override
            public CompletableFuture<CodeLens> resolveCodeLens(CodeLens unresolved) {
                return null;
            }

            @Override
            public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
                return null;
            }

            @Override
            public CompletableFuture<List<? extends TextEdit>> rangeFormatting(DocumentRangeFormattingParams params) {
                return null;
            }

            @Override
            public CompletableFuture<List<? extends TextEdit>> onTypeFormatting(DocumentOnTypeFormattingParams params) {
                return null;
            }

            @Override
            public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
                return null;
            }

            @Override
            public void didOpen(DidOpenTextDocumentParams params) {
                try {
                    TextDocumentItem document = params.getTextDocument();
                    URI uri = URI.create(document.getUri());
                    Optional<Path> path = getFilePath(uri);

                    if (path.isPresent()) {
                        String text = document.getText();

                        sourceByPath.put(path.get(), text);

                        doLint(path.get());
                    }
                } catch (NoJavaConfigException e) {
                    throw ShowMessageException.warning(e.getMessage(), e);
                }
            }

            @Override
            public void didChange(DidChangeTextDocumentParams params) {
                VersionedTextDocumentIdentifier document = params.getTextDocument();
                URI uri = URI.create(document.getUri());
                Optional<Path> path = getFilePath(uri);

                if (path.isPresent()) {
                    for (TextDocumentContentChangeEvent change : params.getContentChanges()) {
                        if (change.getRange() == null)
                            sourceByPath.put(path.get(), change.getText());
                        else {
                            String existingText = sourceByPath.get(path.get());
                            String newText = patch(existingText, change);

                            sourceByPath.put(path.get(), newText);
                        }
                    }
                }
            }

            @Override
            public void didClose(DidCloseTextDocumentParams params) {
                TextDocumentIdentifier document = params.getTextDocument();
                URI uri = URI.create(document.getUri());
                Optional<Path> path = getFilePath(uri);

                if (path.isPresent()) {
                    JavacHolder compiler = findCompiler(path.get());
                    JavaFileObject file = findFile(compiler, path.get());

                    // Remove from source cache
                    sourceByPath.remove(path.get());
                }
            }

            @Override
            public void didSave(DidSaveTextDocumentParams params) {
                TextDocumentIdentifier document = params.getTextDocument();
                URI uri = URI.create(document.getUri());
                Optional<Path> path = getFilePath(uri);

                // TODO re-lint dependencies as well as changed files
                if (path.isPresent())
                    doLint(path.get());
            }

            @Override
            public void onPublishDiagnostics(Consumer<PublishDiagnosticsParams> callback) {
                publishDiagnostics = callback;
            }
        };
    }

    private String patch(String sourceText, TextDocumentContentChangeEvent change) {
        try {
            Range range = change.getRange();
            BufferedReader reader = new BufferedReader(new StringReader(sourceText));
            StringWriter writer = new StringWriter();

            // Skip unchanged lines
            int line = 0;

            while (line < range.getStart().getLine()) {
                writer.write(reader.readLine() + '\n');
                line++;
            }

            // Skip unchanged chars
            for (int character = 0; character < range.getStart().getCharacter(); character++)
                writer.write(reader.read());

            // Write replacement text
            writer.write(change.getText());

            // Skip replaced text
            reader.skip(change.getRangeLength());

            // Write remaining text
            while (true) {
                int next = reader.read();

                if (next == -1)
                    return writer.toString();
                else
                    writer.write(next);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Optional<Path> getFilePath(URI uri) {
        if (!uri.getScheme().equals("file"))
            return Optional.empty();
        else
            return Optional.of(Paths.get(uri));
    }

    private void doLint(Path path) {
        LOG.info("Lint " + path);

        DiagnosticCollector<JavaFileObject> errors = new DiagnosticCollector<>();

        JavacHolder compiler = findCompiler(path);
        SymbolIndex index = findIndex(path);
        JavaFileObject file = findFile(compiler, path);

        compiler.onError(errors);

        JCTree.JCCompilationUnit parsed = compiler.parse(file);

        compiler.compile(parsed);

        index.update(parsed, compiler.context);

        publishDiagnostics(Collections.singleton(path), errors);
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return new WorkspaceService() {
            @Override
            public CompletableFuture<List<? extends SymbolInformation>> symbol(WorkspaceSymbolParams params) {
                List<SymbolInformation> infos = indexCache.values()
                                                          .stream()
                                                          .flatMap(symbolIndex -> symbolIndex.search(params.getQuery()))
                                                          .limit(100)
                                                          .collect(Collectors.toList());

                return CompletableFuture.completedFuture(infos);
            }

            @Override
            public void didChangeConfiguraton(DidChangeConfigurationParams params) {

            }

            @Override
            public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
                for (FileEvent event : params.getChanges()) {
                    if (event.getUri().endsWith(".java")) {
                        if (event.getType() == FileChangeType.Deleted) {
                            URI uri = URI.create(event.getUri());

                            getFilePath(uri).ifPresent(path -> {
                                JavacHolder compiler = findCompiler(path);
                                JavaFileObject file = findFile(compiler, path);
                                SymbolIndex index = findIndex(path);

                                compiler.clear(file);
                                index.clear(file.toUri());
                            });
                        }
                    }
                    else if (event.getUri().endsWith("javaconfig.json")) {
                        // TODO invalidate caches when javaconfig.json changes
                    }
                }
            }
        };
    }

    @Override
    public WindowService getWindowService() {
        return new WindowService() {
            @Override
            public void onShowMessage(Consumer<MessageParams> callback) {
                showMessage = callback;
            }

            @Override
            public void onShowMessageRequest(Consumer<ShowMessageRequestParams> callback) {

            }

            @Override
            public void onLogMessage(Consumer<MessageParams> callback) {

            }
        };
    }

    private void publishDiagnostics(Collection<Path> paths, DiagnosticCollector<JavaFileObject> errors) {
        Map<URI, PublishDiagnosticsParamsImpl> files = new HashMap<>();

        paths.forEach(p -> files.put(p.toUri(), newPublishDiagnostics(p.toUri())));

        errors.getDiagnostics().forEach(error -> {
            if (error.getStartPosition() != javax.tools.Diagnostic.NOPOS) {
                URI uri = error.getSource().toUri();
                PublishDiagnosticsParamsImpl publish = files.computeIfAbsent(uri, this::newPublishDiagnostics);

                RangeImpl range = position(error);
                DiagnosticImpl diagnostic = new DiagnosticImpl();
                DiagnosticSeverity severity = severity(error.getKind());

                diagnostic.setSeverity(severity);
                diagnostic.setRange(range);
                diagnostic.setCode(error.getCode());
                diagnostic.setMessage(error.getMessage(null));

                publish.getDiagnostics().add(diagnostic);
            }
        });

        files.values().forEach(publishDiagnostics::accept);
    }

    private DiagnosticSeverity severity(javax.tools.Diagnostic.Kind kind) {
        switch (kind) {
            case ERROR:
                return DiagnosticSeverity.Error;
            case WARNING:
            case MANDATORY_WARNING:
                return DiagnosticSeverity.Warning;
            case NOTE:
            case OTHER:
            default:
                return DiagnosticSeverity.Information;
        }
    }

    private PublishDiagnosticsParamsImpl newPublishDiagnostics(URI newUri) {
        PublishDiagnosticsParamsImpl p = new PublishDiagnosticsParamsImpl();

        p.setDiagnostics(new ArrayList<>());
        p.setUri(newUri.toString());

        return p;
    }

    private Map<JavacConfig, JavacHolder> compilerCache = new HashMap<>();

    /**
     * Instead of looking for javaconfig.json and creating a JavacHolder, just use this.
     * For testing.
     */
    private final Optional<JavacHolder> testJavac;

    /**
     * Look for a configuration in a parent directory of uri
     */
    public JavacHolder findCompiler(Path path) {
        if (testJavac.isPresent())
            return testJavac.get();

        Path dir = path.getParent();
        Optional<JavacConfig> config = findConfig(dir);

        // If config source path doesn't contain source file, then source file has no config
        if (config.isPresent() && !config.get().sourcePath.stream().anyMatch(s -> path.startsWith(s)))
            throw new NoJavaConfigException(path.getFileName() + " is not on the source path");

        Optional<JavacHolder> maybeHolder = config.map(c -> compilerCache.computeIfAbsent(c, this::newJavac));

        return maybeHolder.orElseThrow(() -> new NoJavaConfigException(path));
    }

    private JavacHolder newJavac(JavacConfig c) {
        return new JavacHolder(c.classPath,
                               c.sourcePath,
                               c.outputDirectory);
    }

    private Map<JavacConfig, SymbolIndex> indexCache = new HashMap<>();

    public SymbolIndex findIndex(Path path) {
        Path dir = path.getParent();
        Optional<JavacConfig> config = findConfig(dir);
        Optional<SymbolIndex> index = config.map(c -> indexCache.computeIfAbsent(c, this::newIndex));

        return index.orElseThrow(() -> new NoJavaConfigException(path));
    }

    private SymbolIndex newIndex(JavacConfig c) {
        return new SymbolIndex(c.classPath, c.sourcePath, c.outputDirectory, this::publishDiagnostics);
    }

    // TODO invalidate cache when VSCode notifies us config file has changed
    private Map<Path, Optional<JavacConfig>> configCache = new HashMap<>();

    private Optional<JavacConfig> findConfig(Path dir) {
        return configCache.computeIfAbsent(dir, this::doFindConfig);
    }

    private Optional<JavacConfig> doFindConfig(Path dir) {
        if (testJavac.isPresent())
            return testJavac.map(j -> new JavacConfig(j.sourcePath, j.classPath, j.outputDirectory));

        while (true) {
            Optional<JavacConfig> found = readIfConfig(dir);

            if (found.isPresent())
                return found;
            else if (workspaceRoot.startsWith(dir))
                return Optional.empty();
            else
                dir = dir.getParent();
        }
    }

    /**
     * If directory contains a config file, for example javaconfig.json or an eclipse project file, read it.
     */
    public Optional<JavacConfig> readIfConfig(Path dir) {
        Optional<JavaProjectResolver> projectResolver = JavaProjectResolverLocator.findResolver(dir);
        if(!projectResolver.isPresent()){
            return Optional.empty();
        }

        return projectResolver.map(p-> new JavacConfig(
                p.resolveSourcePath(),
                p.resolveClassPath(),
                p.resolveOutputPath()
        ));
    }

    public JavaFileObject findFile(JavacHolder compiler, Path path) {
        if (sourceByPath.containsKey(path))
            return new StringFileObject(sourceByPath.get(path), path);
        else
            return compiler.fileManager.getRegularFile(path.toFile());
    }

    private RangeImpl position(javax.tools.Diagnostic<? extends JavaFileObject> error) {
        // Compute start position
        PositionImpl start = new PositionImpl();

        start.setLine((int) (error.getLineNumber() - 1));
        start.setCharacter((int) (error.getColumnNumber() - 1));

        // Compute end position
        PositionImpl end = endPosition(error);

        // Combine into Range
        RangeImpl range = new RangeImpl();

        range.setStart(start);
        range.setEnd(end);

        return range;
    }

    private PositionImpl endPosition(javax.tools.Diagnostic<? extends JavaFileObject> error) {
        try (Reader reader = error.getSource().openReader(true)) {
            long startOffset = error.getStartPosition();
            long endOffset = error.getEndPosition();

            reader.skip(startOffset);

            int line = (int) error.getLineNumber() - 1;
            int column = (int) error.getColumnNumber() - 1;

            for (long i = startOffset; i < endOffset; i++) {
                int next = reader.read();

                if (next == '\n') {
                    line++;
                    column = 0;
                }
                else
                    column++;
            }

            PositionImpl end = new PositionImpl();

            end.setLine(line);
            end.setCharacter(column);

            return end;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<? extends Location> findReferences(ReferenceParams params) {
        URI uri = URI.create(params.getTextDocument().getUri());
        int line = params.getPosition().getLine();
        int character = params.getPosition().getCharacter();
        List<Location> result = new ArrayList<>();

        findSymbol(uri, line, character).ifPresent(symbol -> {
            getFilePath(uri).map(this::findIndex).ifPresent(index -> {
                index.references(symbol).forEach(result::add);
            });
        });

        return result;
    }

    private List<? extends SymbolInformation> findDocumentSymbols(DocumentSymbolParams params) {
        URI uri = URI.create(params.getTextDocument().getUri());

        return getFilePath(uri).map(path -> {
            SymbolIndex index = findIndex(path);
            List<? extends SymbolInformation> found = index.allInFile(uri).collect(Collectors.toList());

            return found;
        }).orElse(Collections.emptyList());
    }

    public Optional<Symbol> findSymbol(URI uri, int line, int character) {
        return getFilePath(uri).flatMap(path -> {
            JavacHolder compiler = findCompiler(path);
            SymbolIndex index = findIndex(path);
            JavaFileObject file = findFile(compiler, path);
            long cursor = findOffset(file, line, character);
            SymbolUnderCursorVisitor visitor = new SymbolUnderCursorVisitor(file, cursor, compiler.context);

            compiler.onError(err -> {});

            JCTree.JCCompilationUnit tree = compiler.parse(file);

            compiler.compile(tree);

            index.update(tree, compiler.context);

            tree.accept(visitor);

            return visitor.found;
        });
    }

    public List<? extends Location> gotoDefinition(TextDocumentPositionParams position) {
        URI uri = URI.create(position.getTextDocument().getUri());
        int line = position.getPosition().getLine();
        int character = position.getPosition().getCharacter();
        List<Location> result = new ArrayList<>();

        findSymbol(uri, line, character).ifPresent(symbol -> {
            getFilePath(uri).map(this::findIndex).ifPresent(index -> {
                index.findSymbol(symbol).ifPresent(info -> {
                    result.add(info.getLocation());
                });
            });
        });

        return result;
    }

    public static RangeImpl findPosition(JavaFileObject file, long startOffset, long endOffset) {
        try (Reader in = file.openReader(true)) {
            long offset = 0;
            int line = 0;
            int character = 0;

            // Find the start position
            while (offset < startOffset) {
                int next = in.read();

                if (next < 0)
                    break;
                else {
                    offset++;
                    character++;

                    if (next == '\n') {
                        line++;
                        character = 0;
                    }
                }
            }

            PositionImpl start = createPosition(line, character);

            // Find the end position
            while (offset < endOffset) {
                int next = in.read();

                if (next < 0)
                    break;
                else {
                    offset++;
                    character++;

                    if (next == '\n') {
                        line++;
                        character = 0;
                    }
                }
            }

            PositionImpl end = createPosition(line, character);

            // Combine into range
            RangeImpl range = new RangeImpl();

            range.setStart(start);
            range.setEnd(end);

            return range;
        } catch (IOException e) {
            throw ShowMessageException.error(e.getMessage(), e);
        }
    }

    private static PositionImpl createPosition(int line, int character) {
        PositionImpl p = new PositionImpl();

        p.setLine(line);
        p.setCharacter(character);

        return p;
    }

    public static long findOffset(JavaFileObject file, int targetLine, int targetCharacter) {
        try (Reader in = file.openReader(true)) {
            long offset = 0;
            int line = 0;
            int character = 0;

            while (line < targetLine) {
                int next = in.read();

                if (next < 0)
                    return offset;
                else {
                    offset++;

                    if (next == '\n')
                        line++;
                }
            }

            while (character < targetCharacter) {
                int next = in.read();

                if (next < 0)
                    return offset;
                else {
                    offset++;
                    character++;
                }
            }

            return offset;
        } catch (IOException e) {
            throw ShowMessageException.error(e.getMessage(), e);
        }
    }

    public HoverImpl doHover(TextDocumentPositionParams position) {
        HoverImpl result = new HoverImpl();
        List<MarkedStringImpl> contents = new ArrayList<>();

        result.setContents(contents);

        Optional<Path> maybePath = getFilePath(URI.create(position.getTextDocument().getUri()));

        if (maybePath.isPresent()) {
            Path path = maybePath.get();
            Optional<Symbol> found = findSymbol(path.toUri(),
                                                position.getPosition().getLine(),
                                                position.getPosition().getCharacter()
            );
            if (found.isPresent()) {
                Symbol symbol = found.get();

                switch (symbol.getKind()) {
                    case PACKAGE:
                        contents.add(markedString("package " + symbol.getQualifiedName()));

                        break;
                    case ENUM:
                        contents.add(markedString("enum " + symbol.getQualifiedName()));

                        break;
                    case CLASS:
                        contents.add(markedString("class " + symbol.getQualifiedName()));

                        break;
                    case ANNOTATION_TYPE:
                        contents.add(markedString("@interface " + symbol.getQualifiedName()));

                        break;
                    case INTERFACE:
                        contents.add(markedString("interface " + symbol.getQualifiedName()));

                        break;
                    case METHOD:
                    case CONSTRUCTOR:
                    case STATIC_INIT:
                    case INSTANCE_INIT:
                        Symbol.MethodSymbol method = (Symbol.MethodSymbol) symbol;
                        String signature = AutocompleteVisitor.methodSignature(method);
                        String returnType = ShortTypePrinter.print(method.getReturnType());

                        contents.add(markedString(returnType + " " + signature));

                        break;
                    case PARAMETER:
                    case LOCAL_VARIABLE:
                    case EXCEPTION_PARAMETER:
                    case ENUM_CONSTANT:
                    case FIELD:
                        contents.add(markedString(ShortTypePrinter.print(symbol.type)));

                        break;
                    case TYPE_PARAMETER:
                    case OTHER:
                    case RESOURCE_VARIABLE:
                        break;
                }
            }
        }

        return result;
    }

    private MarkedStringImpl markedString(String value) {
        MarkedStringImpl result = new MarkedStringImpl();

        result.setLanguage("java");
        result.setValue(value);

        return result;
    }

    public CompletionList autocomplete(TextDocumentPositionParams position) {
        CompletionListImpl result = new CompletionListImpl();

        result.setIncomplete(false);
        result.setItems(new ArrayList<>());

        Optional<Path> maybePath = getFilePath(URI.create(position.getTextDocument().getUri()));

        if (maybePath.isPresent()) {
            Path path = maybePath.get();
            DiagnosticCollector<JavaFileObject> errors = new DiagnosticCollector<>();
            JavacHolder compiler = findCompiler(path);
            JavaFileObject file = findFile(compiler, path);
            long cursor = findOffset(file, position.getPosition().getLine(), position.getPosition().getCharacter());
            JavaFileObject withSemi = withSemicolonAfterCursor(file, path, cursor);
            AutocompleteVisitor autocompleter = new AutocompleteVisitor(withSemi, cursor, compiler.context);

            compiler.onError(errors);

            JCTree.JCCompilationUnit ast = compiler.parse(withSemi);

            // Remove all statements after the cursor
            // There are often parse errors after the cursor, which can generate unrecoverable type errors
            ast.accept(new AutocompletePruner(withSemi, cursor, compiler.context));

            compiler.compile(ast);

            ast.accept(autocompleter);

            result.getItems().addAll(autocompleter.suggestions);
        }

        return result;
    }

    /**
     * Insert ';' after the users cursor so we recover from parse errors in a helpful way when doing autocomplete.
     */
    private JavaFileObject withSemicolonAfterCursor(JavaFileObject file, Path path, long cursor) {
        try (Reader reader = file.openReader(true)) {
            StringBuilder acc = new StringBuilder();

            for (int i = 0; i < cursor; i++) {
                int next = reader.read();

                if (next == -1)
                    throw new RuntimeException("End of file " + file + " before cursor " + cursor);

                acc.append((char) next);
            }

            acc.append(";");

            for (int next = reader.read(); next > 0; next = reader.read()) {
                acc.append((char) next);
            }

            return new StringFileObject(acc.toString(), path);
        } catch (IOException e) {
            throw ShowMessageException.error("Error reading " + file, e);
        }
    }
}
