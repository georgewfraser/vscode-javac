java -cp $(cat cp.txt):$(pwd)/target/classes:$(pwd)/target/test-classes org.junit.runner.JUnitCore \
    org.javacs_server.ArtifactTest \
    org.javacs_server.ClassesTest \
    org.javacs_server.CodeLensTest \
    org.javacs_server.CompletionsScopesTest \
    org.javacs_server.CompletionsTest \
    org.javacs_server.DocsTest \
    org.javacs_server.FindReferencesTest \
    org.javacs_server.GotoTest \
    org.javacs_server.InferBazelConfigTest \
    org.javacs_server.InferConfigTest \
    org.javacs_server.JavaCompilerServiceTest \
    org.javacs_server.ParserFixImportsTest \
    org.javacs_server.ParserTest \
    org.javacs_server.PrunerTest \
    org.javacs_server.SearchTest \
    org.javacs_server.SignatureHelpTest \
    org.javacs_server.SymbolUnderCursorTest \
    org.javacs_server.TipFormatterTest \
    LanguageServerTest \
    LspTest 