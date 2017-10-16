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

package org.javacs.pubapi;

import static javax.lang.model.element.Modifier.PRIVATE;

import com.sun.tools.javac.code.Symbol.ClassSymbol;
import java.util.List;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementScanner8;

/**
 * Utility class that constructs a textual representation of the public api of a class.
 *
 * <p><b>This is NOT part of any supported API. If you write code that depends on this, you do so at
 * your own risk. This code and its internal interfaces are subject to change or deletion without
 * notice.</b>
 */
public class PubapiVisitor extends ElementScanner8<Void, Void> {

    private PubApi collectedApi = new PubApi();

    private boolean isNonPrivate(Element e) {
        return !e.getModifiers().contains(PRIVATE);
    }

    @Override
    public Void visitType(TypeElement e, Void p) {
        if (isNonPrivate(e)) {
            PubApi prevApi = collectedApi;
            collectedApi = new PubApi();
            super.visitType(e, p);
            if (!isAnonymous(e)) {
                String name = ((ClassSymbol) e).flatname.toString();
                PubType t =
                        new PubType(
                                e.getModifiers(),
                                name,
                                //e.getQualifiedName().toString(),
                                collectedApi);
                prevApi.types.put(t.fqName, t);
            }
            collectedApi = prevApi;
        }
        return null;
    }

    private boolean isAnonymous(TypeElement e) {
        return e.getQualifiedName().length() == 0;
    }

    private static String encodeChar(int c) {
        return String.format("\\u%04x", c);
    }

    @Override
    public Void visitVariable(VariableElement e, Void p) {
        if (isNonPrivate(e)) {
            Object constVal = e.getConstantValue();
            String constValStr = null;
            // TODO: This doesn't seem to be entirely accurate. What if I change
            // from, say, 0 to 0L? (And the field is public final static so that
            // it could get inlined.)
            if (constVal != null) {
                if (e.asType().toString().equals("char")) {
                    // What type is 'value'? Is it already a char?
                    char c = constVal.toString().charAt(0);
                    constValStr = "'" + encodeChar(c) + "'";
                } else {
                    constValStr =
                            constVal.toString()
                                    .chars()
                                    .mapToObj(PubapiVisitor::encodeChar)
                                    .collect(Collectors.joining("", "\"", "\""));
                }
            }

            PubVar v =
                    new PubVar(
                            e.getModifiers(),
                            TypeDesc.fromType(e.asType()),
                            e.toString(),
                            constValStr);
            collectedApi.variables.put(v.identifier, v);
        }

        // Safe to not recurse here, because the only thing
        // to visit here is the constructor of a variable declaration.
        // If it happens to contain an anonymous inner class (which it might)
        // then this class is never visible outside of the package anyway, so
        // we are allowed to ignore it here.
        return null;
    }

    @Override
    public Void visitExecutable(ExecutableElement e, Void p) {
        if (isNonPrivate(e)) {
            PubMethod m =
                    new PubMethod(
                            e.getModifiers(),
                            getTypeParameters(e.getTypeParameters()),
                            TypeDesc.fromType(e.getReturnType()),
                            e.getSimpleName().toString(),
                            getTypeDescs(getParamTypes(e)),
                            getTypeDescs(e.getThrownTypes()));
            collectedApi.methods.put(m.asSignatureString(), m);
        }
        return null;
    }

    private List<PubApiTypeParam> getTypeParameters(List<? extends TypeParameterElement> elements) {
        return elements.stream()
                .map(
                        e ->
                                new PubApiTypeParam(
                                        e.getSimpleName().toString(), getTypeDescs(e.getBounds())))
                .collect(Collectors.toList());
    }

    private List<TypeMirror> getParamTypes(ExecutableElement e) {
        return e.getParameters().stream().map(VariableElement::asType).collect(Collectors.toList());
    }

    private List<TypeDesc> getTypeDescs(List<? extends TypeMirror> list) {
        return list.stream().map(TypeDesc::fromType).collect(Collectors.toList());
    }

    public PubApi getCollectedPubApi() {
        return collectedApi;
    }
}
