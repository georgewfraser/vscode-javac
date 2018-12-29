package org.javacs.lsp;

public class Range {
    public Position start, end;

    public Range() {}

    public Range(Position start, Position end) {
        this.start = start;
        this.end = end;
    }
}
