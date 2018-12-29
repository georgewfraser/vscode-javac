package org.javacs_server;

import java.util.StringJoiner;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.*;
import javax.lang.model.util.AbstractTypeVisitor8;

class ShortTypePrinter extends AbstractTypeVisitor8<String, Void> {
    private static final Logger LOG = Logger.getLogger("main");

    private ShortTypePrinter() {}

    static String print(TypeMirror type) {
        return type.accept(new ShortTypePrinter(), null);
    }

    @Override
    public String visitIntersection(IntersectionType t, Void aVoid) {
        return t.getBounds().stream().map(ShortTypePrinter::print).collect(Collectors.joining(" & "));
    }

    @Override
    public String visitUnion(UnionType t, Void aVoid) {
        return t.getAlternatives().stream().map(ShortTypePrinter::print).collect(Collectors.joining(" | "));
    }

    @Override
    public String visitPrimitive(PrimitiveType t, Void aVoid) {
        return t.toString();
    }

    @Override
    public String visitNull(NullType t, Void aVoid) {
        return t.toString();
    }

    @Override
    public String visitArray(ArrayType t, Void aVoid) {
        return print(t.getComponentType()) + "[]";
    }

    @Override
    public String visitDeclared(DeclaredType t, Void aVoid) {
        var result = t.asElement().toString();

        if (!t.getTypeArguments().isEmpty()) {
            String params =
                    t.getTypeArguments().stream().map(ShortTypePrinter::print).collect(Collectors.joining(", "));

            result += "<" + params + ">";
        }

        if (result.matches("java\\.lang\\.\\w+")) return result.substring("java.lang.".length());
        else if (result.startsWith("java\\.util\\.\\w+")) return result.substring("java.util.".length());
        else return result;
    }

    @Override
    public String visitError(ErrorType t, Void aVoid) {
        return "???";
    }

    @Override
    public String visitTypeVariable(TypeVariable t, Void aVoid) {
        String result = t.asElement().toString();
        TypeMirror upper = t.getUpperBound();

        // NOTE this can create infinite recursion
        // if (!upper.toString().equals("java.lang.Object"))
        //     result += " extends " + print(upper);

        return result;
    }

    @Override
    public String visitWildcard(WildcardType t, Void aVoid) {
        String result = "?";

        if (t.getSuperBound() != null) result += " super " + print(t.getSuperBound());

        if (t.getExtendsBound() != null) result += " extends " + print(t.getExtendsBound());

        return result;
    }

    @Override
    public String visitExecutable(ExecutableType t, Void aVoid) {
        return t.toString();
    }

    @Override
    public String visitNoType(NoType t, Void aVoid) {
        return t.toString();
    }

    static boolean missingParamNames(ExecutableElement e) {
        return e.getParameters().stream().allMatch(p -> p.getSimpleName().toString().matches("arg\\d+"));
    }

    private static String printArguments(ExecutableElement e) {
        var result = new StringJoiner(", ");
        var missingParamNames = missingParamNames(e);
        for (var p : e.getParameters()) {
            var s = new StringBuilder();
            s.append(ShortTypePrinter.print(p.asType()));
            if (!missingParamNames) {
                s.append(" ").append(p.getSimpleName());
            }
            result.add(s);
        }
        return result.toString();
    }

    static String printMethod(ExecutableElement m) {
        if (m.getSimpleName().contentEquals("<init>")) {
            return m.getEnclosingElement().getSimpleName() + "(" + printArguments(m) + ")";
        } else {
            var result = new StringBuilder();
            // static void foo
            if (m.getModifiers().contains(Modifier.STATIC)) result.append("static ");
            result.append(ShortTypePrinter.print(m.getReturnType())).append(" ");
            result.append(m.getSimpleName());
            // (int arg, String other)
            result.append("(").append(printArguments(m)).append(")");
            // throws Foo, Bar
            if (!m.getThrownTypes().isEmpty()) {
                result.append(" throws ");
                var types = new StringJoiner(", ");
                for (var t : m.getThrownTypes()) {
                    types.add(ShortTypePrinter.print(t));
                }
                result.append(types);
            }
            return result.toString();
        }
    }
}
