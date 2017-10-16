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

import com.sun.javadoc.MethodDoc;
import com.sun.javadoc.ProgramElementDoc;
import com.sun.tools.javac.code.Type;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

public class Hovers {

    public static Hover hoverText(Element el, Javadocs docs) {
        Optional<String> doc = docs.doc(el).map(Hovers::commentText).map(Javadocs::htmlToMarkdown);
        String sig = signature(el);
        String result =
                doc.map(text -> String.format("```java\n%s\n```\n%s", sig, text)).orElse(sig);

        return new Hover(Collections.singletonList(Either.forLeft(result)), null);
    }

    private static String commentText(ProgramElementDoc doc) {
        if (doc instanceof MethodDoc) {
            MethodDoc method = (MethodDoc) doc;

            return Javadocs.commentText(method).orElse("");
        } else return doc.commentText();
    }

    private static String signature(Element el) {
        if (el.getKind() == ElementKind.CONSTRUCTOR) {
            ExecutableElement method = (ExecutableElement) el;
            TypeElement enclosingClass = (TypeElement) method.getEnclosingElement();

            return enclosingClass.getQualifiedName() + "(" + params(method.getParameters()) + ")";
        }
        if (el instanceof ExecutableElement) {
            ExecutableElement method = (ExecutableElement) el;

            return method.getReturnType()
                    + " "
                    + method.getSimpleName()
                    + "("
                    + params(method.getParameters())
                    + ")";
        } else if (el instanceof TypeElement) {
            TypeElement type = (TypeElement) el;

            return type.getQualifiedName().toString();
        } else return el.asType().toString();
    }

    private static String params(List<? extends VariableElement> params) {
        return params.stream().map(p -> p.asType().toString()).collect(Collectors.joining(", "));
    }

    public static String methodSignature(
            ExecutableElement e, boolean showReturn, boolean showMethodName) {
        String name =
                e.getKind() == ElementKind.CONSTRUCTOR
                        ? constructorName(e)
                        : e.getSimpleName().toString();
        boolean varargs = e.isVarArgs();
        StringJoiner params = new StringJoiner(", ");

        List<? extends VariableElement> parameters = e.getParameters();
        for (int i = 0; i < parameters.size(); i++) {
            VariableElement p = parameters.get(i);
            String pName = shortName(p, varargs && i == parameters.size() - 1);

            params.add(pName);
        }

        String signature = "";

        if (showReturn) signature += ShortTypePrinter.print(e.getReturnType()) + " ";

        if (showMethodName) signature += name;

        signature += "(" + params + ")";

        if (!e.getThrownTypes().isEmpty()) {
            StringJoiner thrown = new StringJoiner(", ");

            for (TypeMirror t : e.getThrownTypes()) thrown.add(ShortTypePrinter.print(t));

            signature += " throws " + thrown;
        }

        return signature;
    }

    public static String shortName(VariableElement p, boolean varargs) {
        TypeMirror type = p.asType();

        if (varargs) {
            Type.ArrayType array = (Type.ArrayType) type;

            type = array.getComponentType();
        }

        String acc = shortTypeName(type);
        String name = p.getSimpleName().toString();

        if (varargs) acc += "...";

        if (!name.matches("arg\\d+")) acc += " " + name;

        return acc;
    }

    private static String shortTypeName(TypeMirror type) {
        return ShortTypePrinter.print(type);
    }

    private static String constructorName(ExecutableElement e) {
        return e.getEnclosingElement().getSimpleName().toString();
    }
}
