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

import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.lang.model.element.ElementKind;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ErrorType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.NoType;
import javax.lang.model.type.NullType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.UnionType;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.AbstractTypeVisitor8;

class ShortTypePrinter extends AbstractTypeVisitor8<String, Void> {
    private static final Logger LOG = Logger.getLogger("main");

    private ShortTypePrinter() {}

    static String print(TypeMirror type) {
        return type.accept(new ShortTypePrinter(), null);
    }

    @Override
    public String visitIntersection(IntersectionType t, Void aVoid) {
        return t.getBounds()
                .stream()
                .map(ShortTypePrinter::print)
                .collect(Collectors.joining(" & "));
    }

    @Override
    public String visitUnion(UnionType t, Void aVoid) {
        return t.getAlternatives()
                .stream()
                .map(ShortTypePrinter::print)
                .collect(Collectors.joining(" | "));
    }

    @Override
    public String visitPrimitive(PrimitiveType t, Void aVoid) {
        return t.toString();
    }

    @Override
    public String visitNull(NullType t, Void aVoid) {
        return t.toString();
    }

    @Override
    public String visitArray(ArrayType t, Void aVoid) {
        return print(t.getComponentType()) + "[]";
    }

    @Override
    public String visitDeclared(DeclaredType t, Void aVoid) {
        String result = "";

        // If type is an inner class, add outer class name
        if (t.asElement().getKind() == ElementKind.CLASS
                && t.getEnclosingType().getKind() == TypeKind.DECLARED) {

            result += print(t.getEnclosingType()) + ".";
        }

        result += t.asElement().getSimpleName().toString();

        if (!t.getTypeArguments().isEmpty()) {
            String params =
                    t.getTypeArguments()
                            .stream()
                            .map(ShortTypePrinter::print)
                            .collect(Collectors.joining(", "));

            result += "<" + params + ">";
        }

        return result;
    }

    @Override
    public String visitError(ErrorType t, Void aVoid) {
        return "???";
    }

    @Override
    public String visitTypeVariable(TypeVariable t, Void aVoid) {
        String result = t.asElement().toString();
        TypeMirror upper = t.getUpperBound();

        // NOTE this can create infinite recursion
        // if (!upper.toString().equals("java.lang.Object"))
        //     result += " extends " + print(upper);

        return result;
    }

    @Override
    public String visitWildcard(WildcardType t, Void aVoid) {
        String result = "?";

        if (t.getSuperBound() != null) result += " super " + print(t.getSuperBound());

        if (t.getExtendsBound() != null) result += " extends " + print(t.getExtendsBound());

        return result;
    }

    @Override
    public String visitExecutable(ExecutableType t, Void aVoid) {
        return t.toString();
    }

    @Override
    public String visitNoType(NoType t, Void aVoid) {
        return t.toString();
    }
}
