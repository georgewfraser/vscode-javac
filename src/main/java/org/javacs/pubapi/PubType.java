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
import java.util.Set;
import javax.lang.model.element.Modifier;

public class PubType implements Serializable {

    private static final long serialVersionUID = -7423416049253889793L;

    public final Set<Modifier> modifiers;
    public final String fqName;
    public final PubApi pubApi;

    public PubType(Set<Modifier> modifiers, String fqName, PubApi pubApi) {
        this.modifiers = modifiers;
        this.fqName = fqName;
        this.pubApi = pubApi;
    }

    public String getFqName() {
        return fqName.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (getClass() != obj.getClass()) return false;
        PubType other = (PubType) obj;
        return modifiers.equals(other.modifiers)
                && fqName.equals(other.fqName)
                && pubApi.equals(other.pubApi);
    }

    @Override
    public int hashCode() {
        return modifiers.hashCode() ^ fqName.hashCode() ^ pubApi.hashCode();
    }

    @Override
    public String toString() {
        return String.format(
                "%s[modifiers: %s, fqName: %s, pubApi: %s]",
                getClass().getSimpleName(), modifiers, fqName, pubApi);
    }
}
