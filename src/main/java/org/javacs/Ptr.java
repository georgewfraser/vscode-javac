package org.javacs;

import com.sun.source.tree.*;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreeScanner;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.logging.Logger;
import javax.lang.model.element.*;
import javax.lang.model.type.*;

/** Ptr is a reference to a named element, that can be serialized into a String. */
class Ptr {
    private final String packageName, className;
    private final Optional<String> memberName;
    private final Optional<List<String>> erasedParameterTypes;

    static Ptr toClass(String packageName, String className) {
        return new Ptr(packageName, className);
    }

    private Ptr(String packageName, String className) {
        this.packageName = packageName;
        this.className = className;
        this.memberName = Optional.empty();
        this.erasedParameterTypes = Optional.empty();
    }

    Ptr(String path) {
        // Split my.pkg/Class#member into my.pkg and Class#member
        var slash = path.indexOf('/');
        if (slash == -1) {
            this.packageName = "";
        } else {
            this.packageName = path.substring(0, slash);
            path = path.substring(slash + 1);
        }

        // Split Class#member into Class and member
        var hash = path.indexOf('#');
        if (hash == -1) {
            this.className = path;
            this.memberName = Optional.empty();
            this.erasedParameterTypes = Optional.empty();
            return;
        }
        this.className = path.substring(0, hash);
        path = path.substring(hash + 1);

        // Split method(int,java.lang.String) into method and int,java.lang.String
        var paren = path.indexOf('(');
        if (paren == -1) {
            this.memberName = Optional.of(path);
            this.erasedParameterTypes = Optional.empty();
            return;
        }
        this.memberName = Optional.of(path.substring(0, paren));
        path = path.substring(paren + 1, path.length() - 1);

        // Split int,java.lang.String
        if (path.isEmpty()) {
            this.erasedParameterTypes = Optional.of(List.of());
            return;
        }
        var params = path.split(",");
        this.erasedParameterTypes = Optional.of(List.of(params));
    }

    Ptr(Element e) {
        var packageName = "";
        var reversedClassName = new ArrayList<CharSequence>();
        String memberName = null;
        List<String> params = null;
        for (; e != null; e = e.getEnclosingElement()) {
            if (e instanceof PackageElement) {
                var pkg = (PackageElement) e;
                packageName = pkg.getQualifiedName().toString();
            } else if (e instanceof TypeElement) {
                var type = (TypeElement) e;
                reversedClassName.add(type.getSimpleName());
            } else if (e instanceof ExecutableElement) {
                var method = (ExecutableElement) e;
                memberName = method.getSimpleName().toString();
                params = new ArrayList<String>();
                for (var p : method.getParameters()) {
                    var type = p.asType();
                    var erased = erasure(type);
                    if (erased == null) params.add("java.lang.Object");
                    else params.add(erased.toString());
                }
            } else if (e instanceof VariableElement) {
                var field = (VariableElement) e;
                memberName = field.getSimpleName().toString();
            }
        }
        this.packageName = packageName;
        this.className = reverseAndJoin(reversedClassName, ".");
        this.memberName = Optional.ofNullable(memberName);
        this.erasedParameterTypes = Optional.ofNullable(params);
    }

    private static TypeMirror erasure(TypeMirror t) {
        // Erase class by removing arguments
        if (t instanceof DeclaredType) {
            var d = (DeclaredType) t;
            return d.asElement().asType();
        }
        // Erase wildcard to upper bound
        if (t instanceof WildcardType) {
            var w = (WildcardType) t;
            return w.getExtendsBound();
        }
        // Erase type var to upper bound
        if (t instanceof TypeVariable) {
            var v = (TypeVariable) t;
            return v.getUpperBound();
        }
        return t;
    }

    private static String reverseAndJoin(List<CharSequence> parts, String sep) {
        var join = new StringJoiner(sep);
        for (var i = parts.size() - 1; i >= 0; i--) {
            join.add(parts.get(i));
        }
        return join.toString();
    }

    // TODO eliminate className() everywhere in the codebase in favor of simpleClassName() and qualifiedClassName()
    String qualifiedClassName() {
        if (packageName.isEmpty()) return className;
        return packageName + "." + className;
    }

    static final int NOT_MATCHED = 100;

    int fuzzyMatch(TreePath path) {
        if (!packageName(path).equals(packageName)) return NOT_MATCHED;
        if (!simpleClassName(path).equals(className)) return NOT_MATCHED;
        // Methods
        if (erasedParameterTypes.isPresent()) {
            if (!(path.getLeaf() instanceof MethodTree)) return NOT_MATCHED;
            var method = (MethodTree) path.getLeaf();
            if (!method.getName().contentEquals(memberName.get())) return NOT_MATCHED;
            if (method.getParameters().size() != erasedParameterTypes.get().size()) return NOT_MATCHED;
            var mismatch = 0;
            for (var i = 0; i < method.getParameters().size(); i++) {
                var type = method.getParameters().get(i).getType();
                var name = fuzzyTypeName(type);
                var expected = erasedParameterTypes.get().get(i);
                if (!expected.endsWith(name)) mismatch++;
            }
            return mismatch;
        }
        // Fields
        if (memberName.isPresent()) {
            if (!(path.getLeaf() instanceof VariableTree)) return NOT_MATCHED;
            var field = (VariableTree) path.getLeaf();
            if (!field.getName().contentEquals(memberName.get())) return NOT_MATCHED;
            return 0;
        }
        // Classes
        return 0;
    }

    private String packageName(TreePath path) {
        return Objects.toString(path.getCompilationUnit().getPackageName(), "");
    }

    private String simpleClassName(TreePath path) {
        var reversedClassName = new ArrayList<CharSequence>();
        for (; path != null; path = path.getParentPath()) {
            if (path.getLeaf() instanceof ClassTree) {
                var cls = (ClassTree) path.getLeaf();
                reversedClassName.add(cls.getSimpleName());
            }
        }
        return reverseAndJoin(reversedClassName, ".");
    }

    private String fuzzyTypeName(Tree type) {
        class FindTypeName extends TreeScanner<Void, Void> {
            String found = "";

            @Override
            public Void visitIdentifier(IdentifierTree t, Void __) {
                found = t.getName().toString();
                return null;
            }

            @Override
            public Void visitPrimitiveType(PrimitiveTypeTree t, Void __) {
                found = t.getPrimitiveTypeKind().name();
                return null;
            }
        }
        var find = new FindTypeName();
        find.scan(type, null);
        if (find.found.isEmpty()) {
            LOG.warning(
                    String.format(
                            "Couldn't find type name for %s `%s`",
                            type.getClass().getName(), ParseFile.describeTree(type)));
        }
        return find.found;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Ptr)) return false;
        var that = (Ptr) other;
        return Objects.equals(this.packageName, that.packageName)
                && Objects.equals(this.className, that.className)
                && Objects.equals(that.memberName, that.memberName)
                && Objects.equals(this.erasedParameterTypes, that.erasedParameterTypes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(packageName, className, memberName, erasedParameterTypes);
    }

    @Override
    public String toString() {
        var s = new StringBuilder();
        if (!packageName.isEmpty()) {
            s.append(packageName).append('/');
        }
        s.append(className);
        if (memberName.isPresent()) {
            s.append('#').append(memberName.get());
        }
        if (erasedParameterTypes.isPresent()) {
            var join = String.join(",", erasedParameterTypes.get());
            s.append('(').append(join).append(')');
        }
        return s.toString();
    }

    private static final Logger LOG = Logger.getLogger("main");
}
