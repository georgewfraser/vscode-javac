# Todo

## Bugs
* autoc|() should'nt add an extra () before the existing one
* import static org.| doesn't auto-complete
* array.length doesn't auto-complete
* Hover doesn't show 'throws ...'
* Enum const javadocs are not showing
* Missing ; lint has no width
* Autocomplete new MethodScopeClass| adds 'import .MethodScopeClass'
* implements | doesn't autocomplete
* super.[protected member] doesn't autocomplete
* When there is an annotation processor in the *client* dependencies, linting fails
* When file is open but no longer on disk, language server fails with
    ```
    Caused by: java.lang.RuntimeException: java.nio.file.NoSuchFileException: /Users/george/Documents/vscode-javac/src/test/java/org/javacs/MainTest.java
        at org.javacs.SymbolIndex.needsUpdate(SymbolIndex.java:86)
        at org.javacs.SymbolIndex.updateFile(SymbolIndex.java:70)
        at java.util.Iterator.forEachRemaining(Iterator.java:116)
        at java.util.Spliterators$IteratorSpliterator.forEachRemaining(Spliterators.java:1801)
        at java.util.stream.ReferencePipeline$Head.forEach(ReferencePipeline.java:580)
        at org.javacs.SymbolIndex.updateIndex(SymbolIndex.java:66)
        at org.javacs.SymbolIndex.updateOpenFiles(SymbolIndex.java:152)
        at org.javacs.SymbolIndex.sourcePath(SymbolIndex.java:100)
        at org.javacs.JavaLanguageServer.configured(JavaLanguageServer.java:59)
        at org.javacs.JavaTextDocumentService.hover(JavaTextDocumentService.java:156)
        ... 15 more
    Caused by: java.nio.file.NoSuchFileException: /Users/george/Documents/vscode-javac/src/test/java/org/javacs/MainTest.java
        at sun.nio.fs.UnixException.translateToIOException(UnixException.java:86)
        at sun.nio.fs.UnixException.rethrowAsIOException(UnixException.java:102)
        at sun.nio.fs.UnixException.rethrowAsIOException(UnixException.java:107)
        at sun.nio.fs.UnixFileAttributeViews$Basic.readAttributes(UnixFileAttributeViews.java:55)
        at sun.nio.fs.UnixFileSystemProvider.readAttributes(UnixFileSystemProvider.java:144)
        at java.nio.file.Files.readAttributes(Files.java:1737)
        at java.nio.file.Files.getLastModifiedTime(Files.java:2266)
        at org.javacs.SymbolIndex.needsUpdate(SymbolIndex.java:82)
        ... 24 more
    ```

## Default configuration
* Alert if we can't find a dependency
* Support module-info.java as a way to limit autocomplete and provide compile-time 'symbol not found'

## Polish
* Status bar info during indexing
* Convert {@tag ...} to `<tag>...</tag>` (see vscode-java)

## Autocomplete
* Annotation fields
* Enum options in switch statement
* Other methods of class when we have already statically imported 1 method
* Interface name for anonymous class new Runnable() { }
* Order members stream inherited-last

## Features 
* Go-to-subclasses
* Reformat selection, file

## Code actions
* Explode import *
* Auto-add 'throws ?'
* Unused return foo() => String ? = foo()

### Refactoring
* Inline method, variable
* Extract method, variable
* Replace for comprehension with loop

### Code generation
* New .java file class boilerplate
* Missing method definition
* Override method
* Add variable
* Enum options
* Cast to type
* Import missing file
* Unused return value auto-add

### Code lens
* "N references" on method, class
* "N inherited" on class, with generate-override actions

## Lint
* Add 3rd-party linter (findbugs?)