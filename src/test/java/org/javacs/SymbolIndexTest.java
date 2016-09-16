package org.javacs;

import com.sun.tools.javac.code.Symbol;
import io.typefox.lsapi.*;
import javax.tools.*;
import com.sun.tools.javac.tree.*;
import org.javacs.resolver.MavenProjectResolver;
import org.junit.Ignore;
import org.junit.Test;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;

public class SymbolIndexTest {
    private static final Logger LOG = Logger.getLogger("main");

    @Test
    public void all() {
        Set<String> all = search("");

        assertThat(all, not(empty()));
    }

    @Test
    public void searchClasses() {
        Set<String> all = search("JLanguageServer");

        assertThat(all, hasItem("JavaLanguageServer"));
    }

    @Test
    public void searchMethods() {
        Set<String> all = search("gTextDocumentService");

        assertThat(all, hasItem("getTextDocumentService"));
    }

    @Test
    public void referenceConstructor() {
        String path = "/org/javacs/example/ReferenceConstructor.java";
        int line = 2;
        int character = 22;

        compile(path);

        Symbol classSymbol = symbol(path, line, character);
        List<Integer> references = index.references(classSymbol)
                                        .map(ref -> ref.getRange().getStart().getLine())
                                        .collect(Collectors.toList());

        // Constructor reference on line 8
        assertThat(references, hasItem(8));
    }

    @Test
    public void symbolsInFile() {
        String path = "/org/javacs/example/AutocompleteMember.java";

        compile(path);

        List<String> all = index.allInFile(new GetResourceFileObject(path).toUri())
                                .map(s -> s.getName())
                                .collect(Collectors.toList());

        assertThat(all, hasItems("methodStatic", "method",
                                 "methodStaticPrivate", "methodPrivate"));

        assertThat(all, hasItems("fieldStatic", "field",
                                 "fieldStaticPrivate", "fieldPrivate"));

        // TODO
        // assertThat("excludes implicit constructor", all, not(hasItems("AutocompleteMember")));
    }

    @Test
    public void explicitConstructor() {
        String path = "/org/javacs/example/ReferenceConstructor.java";

        compile(path);

        List<String> all = index.allInFile(new GetResourceFileObject(path).toUri())
                                .map(s -> s.getName())
                                .collect(Collectors.toList());

        assertThat("includes explicit constructor", all, hasItem("ReferenceConstructor"));
    }

    private Symbol symbol(String path, int line, int character) {
        try {
            URI uri = GetResourceFileObject.class.getResource(path).toURI();

            return new JavaLanguageServer(compiler).findSymbol(uri, line, character).orElse(null);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    private JCTree.JCCompilationUnit compile(String path) {
        JavaFileObject file = new GetResourceFileObject(path);
        JCTree.JCCompilationUnit tree = compiler.parse(file);

        compiler.compile(tree);
        index.update(tree, compiler.context);

        return tree;
    }

    private Set<String> search(String query) {
        return index.search(query).map(s -> s.getName()).collect(Collectors.toSet());
    }

    private static Set<Path> classPath = new MavenProjectResolver().init(
            Paths.get("./"),
            Paths.get("pom.xml")
    ).resolveClassPath();

    private SymbolIndex index = getIndex();
    
    private static SymbolIndex getIndex() {
        Set<Path> sourcePath = Collections.singleton(Paths.get("src/main/java").toAbsolutePath());
        Path outputDirectory = Paths.get("out").toAbsolutePath();
        SymbolIndex index = new SymbolIndex(classPath, sourcePath, outputDirectory, (paths, errs) -> {
            errs.getDiagnostics().forEach(d -> LOG.info(d.getMessage(Locale.US)));
        });

        index.initialIndexComplete.join();

        return index;
    }

    private static JavacHolder compiler = newCompiler();

    private static JavacHolder newCompiler() {
        return new JavacHolder(Collections.emptySet(),
                               Collections.singleton(Paths.get("src/test/resources")),
                               Paths.get("out"));
    }
}