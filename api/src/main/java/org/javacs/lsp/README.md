# LSP module
`org.javacs_server.lsp` is a minimalist implementation of the Language Server Protocol. 
It doesn't necessarily implement all features of the LSP, because it's only intended to serve the purposes of the Java Language Server.

## Why not use https://github.com/eclipse/lsp4j?
One of the design goals of the JLS is to be deployed as a zero-dependency native application,
so that it works correctly regardless of what version of Java you have on your system.
`lsp4j` isn't compatible with the Java Module System, which we need in order to use jlink.