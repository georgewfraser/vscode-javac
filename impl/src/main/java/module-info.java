module javacs_server {
    requires jdk.compiler;
    requires jdk.zipfs;
    requires java.logging;
    requires java.xml;
    requires javacs;
    requires gson;

    uses javax.tools.JavaCompiler;
}
