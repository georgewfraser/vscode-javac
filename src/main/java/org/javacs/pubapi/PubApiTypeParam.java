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
import java.util.stream.Collectors;

public class PubApiTypeParam implements Serializable {

    private static final long serialVersionUID = 8899204612014329162L;

    private final String identifier;
    private final List<TypeDesc> bounds;

    public PubApiTypeParam(String identifier, List<TypeDesc> bounds) {
        this.identifier = identifier;
        this.bounds = bounds;
    }

    @Override
    public boolean equals(Object obj) {
        if (getClass() != obj.getClass()) return false;
        PubApiTypeParam other = (PubApiTypeParam) obj;
        return identifier.equals(other.identifier) && bounds.equals(other.bounds);
    }

    @Override
    public int hashCode() {
        return identifier.hashCode() ^ bounds.hashCode();
    }

    public String asString() {
        if (bounds.isEmpty()) return identifier;
        String boundsStr =
                bounds.stream().map(TypeDesc::encodeAsString).collect(Collectors.joining(" & "));
        return identifier + " extends " + boundsStr;
    }

    @Override
    public String toString() {
        return String.format(
                "%s[id: %s, bounds: %s]", getClass().getSimpleName(), identifier, bounds);
    }
}
