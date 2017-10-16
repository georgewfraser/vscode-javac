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
import javax.lang.model.type.TypeKind;

public class ReferenceTypeDesc extends TypeDesc implements Serializable {

    private static final long serialVersionUID = 3357616754544796372L;

    // Example: "java.util.Vector<java.lang.String>"
    String javaType;

    public ReferenceTypeDesc(String javaType) {
        super(TypeKind.DECLARED);
        this.javaType = javaType;
    }

    @Override
    public boolean equals(Object obj) {
        if (!super.equals(obj)) return false;
        return javaType.equals(((ReferenceTypeDesc) obj).javaType);
    }

    @Override
    public int hashCode() {
        return super.hashCode() ^ javaType.hashCode();
    }

    @Override
    public String toString() {
        return String.format("%s[type: %s]", getClass().getSimpleName(), javaType);
    }
}
