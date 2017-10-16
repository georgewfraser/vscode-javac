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

class ReachableClass {
    final String packageName, className;
    final boolean publicClass, publicConstructor, packagePrivateConstructor, hasTypeParameters;

    ReachableClass(
            String packageName,
            String className,
            boolean publicClass,
            boolean publicConstructor,
            boolean packagePrivateConstructor,
            boolean hasTypeParameters) {
        this.packageName = packageName;
        this.className = className;
        this.publicClass = publicClass;
        this.publicConstructor = publicConstructor;
        this.packagePrivateConstructor = packagePrivateConstructor;
        this.hasTypeParameters = hasTypeParameters;
    }

    String qualifiedName() {
        return packageName.isEmpty() ? className : packageName + "." + className;
    }

    boolean hasAccessibleConstructor(String fromPackage) {
        boolean samePackage = fromPackage.equals(packageName);

        return (publicClass && publicConstructor) || (samePackage && packagePrivateConstructor);
    }
}
