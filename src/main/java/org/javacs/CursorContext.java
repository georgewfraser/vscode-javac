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

import com.sun.source.tree.ImportTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;

enum CursorContext {
    NewClass(Tree.Kind.NEW_CLASS),
    Import(Tree.Kind.IMPORT),
    Reference(Tree.Kind.MEMBER_REFERENCE),
    Other(null);

    final Tree.Kind kind;

    TreePath find(final TreePath path) {
        if (this.kind == null) return path;
        else {
            TreePath search = path;

            while (search != null) {
                if (this.kind == search.getLeaf().getKind()) return search;
                else search = search.getParentPath();
            }

            return path;
        }
    }

    CursorContext(Tree.Kind kind) {
        this.kind = kind;
    }

    /**
     * Is this identifier or member embedded in an important context, for example:
     *
     * <p>new OuterClass.InnerClass| import package.Class|
     */
    static CursorContext from(TreePath path) {
        if (path == null) return Other;
        else
            switch (path.getLeaf().getKind()) {
                case MEMBER_REFERENCE:
                    return Reference;
                case MEMBER_SELECT:
                case IDENTIFIER:
                    return fromIdentifier(path.getParentPath(), path.getLeaf());
                case NEW_CLASS:
                    return NewClass;
                case IMPORT:
                    return Import;
                default:
                    return Other;
            }
    }

    private static CursorContext fromIdentifier(TreePath parent, Tree id) {
        if (parent == null) return Other;
        else
            switch (parent.getLeaf().getKind()) {
                case MEMBER_SELECT:
                case MEMBER_REFERENCE:
                case IDENTIFIER:
                    return fromIdentifier(parent.getParentPath(), parent.getLeaf());
                case NEW_CLASS:
                    {
                        NewClassTree leaf = (NewClassTree) parent.getLeaf();

                        if (leaf.getIdentifier() == id) return NewClass;
                        else return Other;
                    }
                case IMPORT:
                    {
                        ImportTree leaf = (ImportTree) parent.getLeaf();

                        if (leaf.getQualifiedIdentifier() == id) return Import;
                        else return Other;
                    }
                default:
                    return Other;
            }
    }
}
