package org.javacs_server;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.SourcePositions;
import java.util.Set;

class FixImports {
    final CompilationUnitTree parsed;
    final SourcePositions sourcePositions;
    final Set<String> fixedImports;

    FixImports(CompilationUnitTree parsed, SourcePositions sourcePositions, Set<String> fixedImports) {
        this.parsed = parsed;
        this.sourcePositions = sourcePositions;
        this.fixedImports = fixedImports;
    }
}
