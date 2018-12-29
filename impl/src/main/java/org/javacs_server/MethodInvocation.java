package org.javacs_server;

import com.sun.source.tree.ExpressionTree;
import java.util.List;
import java.util.Optional;
import javax.lang.model.element.ExecutableElement;

public class MethodInvocation {
    /** MethodInvocationTree or NewClassTree */
    public final ExpressionTree tree;

    public final Optional<ExecutableElement> activeMethod;
    public final int activeParameter;
    public final List<ExecutableElement> overloads;

    public MethodInvocation(
            ExpressionTree tree,
            Optional<ExecutableElement> activeMethod,
            int activeParameter,
            List<ExecutableElement> overloads) {
        this.tree = tree;
        this.activeMethod = activeMethod;
        this.activeParameter = activeParameter;
        this.overloads = overloads;
    }
}
