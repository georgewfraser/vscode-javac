package org.javacs_server;

import java.util.Set;

class ExistingImports {
    /** Fully-qualified names of classes that have been imported on the source path */
    final Set<String> classes;
    /** Package names from star-imports like `import java.util.*` */
    final Set<String> packages;

    ExistingImports(Set<String> classes, Set<String> packages) {
        this.classes = classes;
        this.packages = packages;
    }
}
