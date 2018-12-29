package org.javacs_server;

import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.*;
import javax.lang.model.util.AbstractTypeVisitor8;

class TemplatePrinter extends AbstractTypeVisitor8<String, Void> {
    private final Logger LOG = Logger.getLogger("main");

    private Map<TypeMirror, Integer> parameters = new HashMap<>();

    private int parameter(TypeMirror t) {
        if (parameters.containsKey(t)) {
            return parameters.get(t);
        } else {
            parameters.put(t, parameters.size() + 1);
            return parameters.get(t);
        }
    }

    String print(TypeMirror type) {
        return type.accept(this, null);
    }

    @Override
    public String visitIntersection(IntersectionType t, Void aVoid) {
        var types = new StringJoiner(" & ");
        for (var b : t.getBounds()) {
            types.add(print(b));
        }
        return "? extends " + types;
    }

    @Override
    public String visitUnion(UnionType t, Void aVoid) {
        return "UNION";
    }

    @Override
    public String visitPrimitive(PrimitiveType t, Void aVoid) {
        return t.toString();
    }

    @Override
    public String visitNull(NullType t, Void aVoid) {
        return "NULL";
    }

    @Override
    public String visitArray(ArrayType t, Void aVoid) {
        return print(t.getComponentType()) + "[]";
    }

    @Override
    public String visitDeclared(DeclaredType t, Void aVoid) {
        var result = t.asElement().getSimpleName().toString();

        if (!t.getTypeArguments().isEmpty()) {
            String params = t.getTypeArguments().stream().map(this::print).collect(Collectors.joining(", "));

            result += "<" + params + ">";
        }

        if (result.matches("java\\.lang\\.\\w+")) return result.substring("java.lang.".length());
        else if (result.startsWith("java\\.util\\.\\w+")) return result.substring("java.util.".length());
        else return result;
    }

    @Override
    public String visitError(ErrorType t, Void aVoid) {
        return "ERROR";
    }

    @Override
    public String visitTypeVariable(TypeVariable t, Void aVoid) {
        return "$" + parameter(t);
    }

    @Override
    public String visitWildcard(WildcardType t, Void aVoid) {
        return "?";
    }

    @Override
    public String visitExecutable(ExecutableType t, Void aVoid) {
        return "EXECUTABLE";
    }

    @Override
    public String visitNoType(NoType t, Void aVoid) {
        return "void";
    }

    private String printArguments(ExecutableElement e) {
        var result = new StringJoiner(", ");
        for (var p : e.getParameters()) {
            var s = new StringBuilder();
            result.add(print(p.asType()) + " " + p.getSimpleName());
        }
        return result.toString();
    }

    String printMethod(ExecutableElement m) {
        if (m.getSimpleName().contentEquals("<init>")) {
            return m.getEnclosingElement().getSimpleName() + "(" + printArguments(m) + ")";
        } else {
            if (m.getModifiers().contains(Modifier.STATIC)) return "ERROR " + m.getSimpleName() + " IS STATIC";
            if (m.getModifiers().contains(Modifier.PRIVATE)) return "ERROR " + m.getSimpleName() + " IS PRIVATE";

            var result = new StringBuilder();
            // public void foo
            if (m.getModifiers().contains(Modifier.PUBLIC)) result.append("public ");
            if (m.getModifiers().contains(Modifier.PROTECTED)) result.append("protected ");
            result.append(print(m.getReturnType())).append(" ");
            result.append(m.getSimpleName());
            // (int arg, String other)
            result.append("(").append(printArguments(m)).append(")");
            // throws Foo, Bar
            if (!m.getThrownTypes().isEmpty()) {
                result.append(" throws ");
                var types = new StringJoiner(", ");
                for (var t : m.getThrownTypes()) {
                    types.add(print(t));
                }
                result.append(types);
            }
            return result.toString();
        }
    }
}
