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

public class ArrayTypeDesc extends TypeDesc implements Serializable {

    private static final long serialVersionUID = -1177329549163314996L;

    TypeDesc compTypeDesc;

    public ArrayTypeDesc(TypeDesc compTypeDesc) {
        super(TypeKind.ARRAY);
        this.compTypeDesc = compTypeDesc;
    }

    @Override
    public boolean equals(Object obj) {
        if (!super.equals(obj)) return false;
        return compTypeDesc.equals(((ArrayTypeDesc) obj).compTypeDesc);
    }

    @Override
    public int hashCode() {
        return super.hashCode() ^ compTypeDesc.hashCode();
    }
}
