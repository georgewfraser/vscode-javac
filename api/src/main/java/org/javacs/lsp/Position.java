package org.javacs.lsp;

public class Position {
    // 0-based
    public int line, character;

    public Position() {}

    public Position(int line, int character) {
        this.line = line;
        this.character = character;
    }
}
