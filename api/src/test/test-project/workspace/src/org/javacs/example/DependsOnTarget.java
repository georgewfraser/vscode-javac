package org.javacs_server.example;

class DependsOnTarget {
    static String name() {
        return Target.name();
    }
}