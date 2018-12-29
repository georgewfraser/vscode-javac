package org.javacs_server;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;

/**
 * Ptr is a reference to a named element, that can be serialized into a String. Ptr considers all overloads to be the
 * same element.
 */
public class Ptr {
    private final String path;

    public Ptr(String path) {
        this.path = path;
    }

    public Ptr(TreePath path) {
        var packageName = path.getCompilationUnit().getPackageName();
        var rev = new ArrayList<CharSequence>();
        while (path != null) {
            var part = path.getLeaf();
            if (part instanceof ClassTree) {
                var cls = (ClassTree) part;
                rev.add(cls.getSimpleName());
            } else if (part instanceof MethodTree) {
                var method = (MethodTree) part;
                // TODO overloads
                rev.add(method.getName());
            } else if (part instanceof VariableTree) {
                var variable = (VariableTree) part;
                rev.add(variable.getName());
            }
            path = path.getParentPath();
        }
        if (packageName != null) rev.add(packageName.toString());
        var name = reverseAndJoin(rev, ".");
        if (!name.matches("(\\w+\\.)*(\\w+|<init>)")) LOG.warning(String.format("`%s` doesn't look like a name", name));
        this.path = name;
    }

    public Ptr(Element e) {
        var rev = new ArrayList<CharSequence>();
        while (e != null) {
            if (e instanceof PackageElement) {
                var pkg = (PackageElement) e;
                if (!pkg.isUnnamed()) rev.add(pkg.getQualifiedName());
            } else if (e instanceof TypeElement) {
                var type = (TypeElement) e;
                rev.add(type.getSimpleName());
            } else if (e instanceof ExecutableElement) {
                var method = (ExecutableElement) e;
                // TODO overloads
                rev.add(method.getSimpleName());
            } else if (e instanceof VariableElement) {
                var field = (VariableElement) e;
                rev.add(field.getSimpleName());
            }
            e = e.getEnclosingElement();
        }
        var name = reverseAndJoin(rev, ".");
        if (!name.matches("(\\w+\\.)*(\\w+|<init>)")) LOG.warning(String.format("`%s` doesn't look like a name", name));
        this.path = name;
    }

    private static String reverseAndJoin(List<CharSequence> parts, String sep) {
        var join = new StringJoiner(sep);
        for (var i = parts.size() - 1; i >= 0; i--) {
            join.add(parts.get(i));
        }
        return join.toString();
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Ptr)) return false;
        var that = (Ptr) other;
        return this.path.equals(that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path);
    }

    public String toString() {
        return path;
    }

    private static final Logger LOG = Logger.getLogger("main");
}
