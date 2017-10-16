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

import java.io.Serializable;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.Modifier;

public class PubMethod implements Serializable {

    private static final long serialVersionUID = -7813050194553446243L;

    Set<Modifier> modifiers;
    List<PubApiTypeParam> typeParams;
    TypeDesc returnType;
    String identifier;
    List<TypeDesc> paramTypes;
    List<TypeDesc> throwDecls;

    public PubMethod(
            Set<Modifier> modifiers,
            List<PubApiTypeParam> typeParams,
            TypeDesc returnType,
            String identifier,
            List<TypeDesc> paramTypes,
            List<TypeDesc> throwDecls) {
        this.modifiers = modifiers;
        this.typeParams = typeParams;
        this.returnType = returnType;
        this.identifier = identifier;
        this.paramTypes = paramTypes;
        this.throwDecls = throwDecls;
    }

    // We need to include return type and type parameters to be sure to have
    // different values for different methods. (A method can be overloaded with
    // the only difference being the upper bound of the return type.)
    public String asSignatureString() {
        StringBuilder sb = new StringBuilder();

        // <A extends String, Serializable, B extends List>
        if (typeParams.size() > 0) {
            sb.append(
                    typeParams
                            .stream()
                            .map(PubApiTypeParam::asString)
                            .collect(Collectors.joining(",", "<", "> ")));
        }
        sb.append(TypeDesc.encodeAsString(returnType));
        sb.append(" ");
        sb.append(identifier);
        sb.append("(");
        sb.append(
                paramTypes.stream().map(TypeDesc::encodeAsString).collect(Collectors.joining(",")));
        sb.append(")");
        return sb.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (getClass() != obj.getClass()) return false;
        PubMethod other = (PubMethod) obj;
        return modifiers.equals(other.modifiers)
                && typeParams.equals(other.typeParams)
                && returnType.equals(other.returnType)
                && identifier.equals(other.identifier)
                && paramTypes.equals(other.paramTypes)
                && throwDecls.equals(other.throwDecls);
    }

    @Override
    public int hashCode() {
        return modifiers.hashCode()
                ^ typeParams.hashCode()
                ^ returnType.hashCode()
                ^ identifier.hashCode()
                ^ paramTypes.hashCode()
                ^ throwDecls.hashCode();
    }

    public String toString() {
        return String.format(
                "%s[modifiers: %s, typeParams: %s, retType: %s, identifier: %s, params: %s, throws: %s]",
                getClass().getSimpleName(),
                modifiers,
                typeParams,
                returnType,
                identifier,
                paramTypes,
                throwDecls);
    }
}
