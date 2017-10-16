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

import com.sun.tools.javac.util.StringUtils;
import java.io.Serializable;
import javax.lang.model.type.TypeKind;

public class PrimitiveTypeDesc extends TypeDesc implements Serializable {

    private static final long serialVersionUID = 6051065543149129106L;

    public PrimitiveTypeDesc(TypeKind typeKind) {
        super(typeKind);
        if (!typeKind.isPrimitive() && typeKind != TypeKind.VOID)
            throw new IllegalArgumentException("Only primitives or void accepted");
    }

    // This class has no fields, so the inherited hashCode and equals should do fine.

    @Override
    public String toString() {
        return StringUtils.toLowerCase(typeKind.toString());
    }
}
