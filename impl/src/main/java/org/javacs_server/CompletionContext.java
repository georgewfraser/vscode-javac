package org.javacs_server;

public class CompletionContext {
    // 1-based
    public final int line, character;
    public final boolean inClass, inMethod;
    public final Kind kind;
    public final String partialName;

    public CompletionContext(
            int line, int character, boolean inClass, boolean inMethod, Kind kind, String partialName) {
        this.line = line;
        this.character = character;
        this.inClass = inClass;
        this.inMethod = inMethod;
        this.kind = kind;
        this.partialName = partialName;
    }

    public enum Kind {
        MemberSelect,
        MemberReference,
        Identifier,
        Annotation,
        Case,
    }
}
