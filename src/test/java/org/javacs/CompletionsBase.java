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

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentPositionParams;

public class CompletionsBase {
    protected static final Logger LOG = Logger.getLogger("main");

    protected Set<String> insertTemplate(String file, int row, int column) throws IOException {
        List<? extends CompletionItem> items = items(file, row, column);

        return items.stream().map(CompletionsBase::itemInsertTemplate).collect(Collectors.toSet());
    }

    static String itemInsertTemplate(CompletionItem i) {
        String text = i.getInsertText();

        if (text == null) text = i.getLabel();

        assert text != null : "Either insertText or label must be defined";

        return text;
    }

    protected Set<String> insertText(String file, int row, int column) throws IOException {
        List<? extends CompletionItem> items = items(file, row, column);

        return items.stream().map(CompletionsBase::itemInsertText).collect(Collectors.toSet());
    }

    protected Map<String, Integer> insertCount(String file, int row, int column)
            throws IOException {
        List<? extends CompletionItem> items = items(file, row, column);
        Map<String, Integer> result = new HashMap<>();

        for (CompletionItem each : items) {
            String key = itemInsertText(each);
            int count = result.getOrDefault(key, 0) + 1;

            result.put(key, count);
        }

        return result;
    }

    static String itemInsertText(CompletionItem i) {
        String text = i.getInsertText();

        if (text == null) text = i.getLabel();

        assert text != null : "Either insertText or label must be defined";

        if (text.endsWith("($0)")) text = text.substring(0, text.length() - "($0)".length());

        return text;
    }

    protected Set<String> documentation(String file, int row, int column) throws IOException {
        List<? extends CompletionItem> items = items(file, row, column);

        return items.stream()
                .flatMap(
                        i -> {
                            if (i.getDocumentation() != null)
                                return Stream.of(i.getDocumentation().trim());
                            else return Stream.empty();
                        })
                .collect(Collectors.toSet());
    }

    protected static final JavaLanguageServer server =
            LanguageServerFixture.getJavaLanguageServer();

    protected List<? extends CompletionItem> items(String file, int row, int column) {
        URI uri = FindResource.uri(file);
        TextDocumentPositionParams position =
                new TextDocumentPositionParams(
                        new TextDocumentIdentifier(uri.toString()),
                        uri.toString(),
                        new Position(row - 1, column - 1));

        try {
            return server.getTextDocumentService().completion(position).get().getRight().getItems();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }
}
