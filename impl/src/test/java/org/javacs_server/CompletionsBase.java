package org.javacs_server;

import com.google.gson.Gson;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.javacs.lsp.CompletionItem;
import org.javacs.lsp.Position;
import org.javacs.lsp.TextDocumentIdentifier;
import org.javacs.lsp.TextDocumentPositionParams;
import org.javacs_server.lsp.*;

public class CompletionsBase {
    protected static final Logger LOG = Logger.getLogger("main");

    protected Set<String> insertTemplate(String file, int row, int column) {
        var items = items(file, row, column);

        return items.stream().map(CompletionsBase::itemInsertTemplate).collect(Collectors.toSet());
    }

    static String itemInsertTemplate(CompletionItem i) {
        var text = i.insertText;

        if (text == null) text = i.label;

        assert text != null : "Either insertText or label must be defined";

        return text;
    }

    protected Set<String> insertText(String file, int row, int column) {
        var items = items(file, row, column);

        return items.stream().map(CompletionsBase::itemInsertText).collect(Collectors.toSet());
    }

    protected Set<String> detail(String file, int row, int column) {
        var items = items(file, row, column);
        var result = new HashSet<String>();
        for (var i : items) {
            i.data = new Gson().toJsonTree(i.data);
            var resolved = resolve(i);
            result.add(resolved.detail);
        }
        return result;
    }

    protected Map<String, Integer> insertCount(String file, int row, int column) {
        var items = items(file, row, column);
        var result = new HashMap<String, Integer>();

        for (var each : items) {
            var key = itemInsertText(each);
            var count = result.getOrDefault(key, 0) + 1;

            result.put(key, count);
        }

        return result;
    }

    static String itemInsertText(CompletionItem i) {
        var text = i.insertText;

        if (text == null) text = i.label;

        assert text != null : "Either insertText or label must be defined";

        if (text.endsWith("($0)")) text = text.substring(0, text.length() - "($0)".length());

        return text;
    }

    protected Set<String> documentation(String file, int row, int column) {
        var items = items(file, row, column);

        return items.stream()
                .flatMap(
                        i -> {
                            if (i.documentation != null) return Stream.of(i.documentation.value.trim());
                            else return Stream.empty();
                        })
                .collect(Collectors.toSet());
    }

    protected static final JavaLanguageServer server = LanguageServerFixture.getJavaLanguageServer();

    protected List<? extends CompletionItem> items(String file, int row, int column) {
        var uri = FindResource.uri(file);
        var position =
                new TextDocumentPositionParams(new TextDocumentIdentifier(uri), new Position(row - 1, column - 1));

        return server.completion(position).get().items;
    }

    protected CompletionItem resolve(CompletionItem item) {
        return server.resolveCompletionItem(item);
    }
}
