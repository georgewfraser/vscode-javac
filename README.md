# VS Code and Language Server Protocol support for Java using the [Java Compiler API](https://docs.oracle.com/javase/7/docs/api/javax/tools/JavaCompiler.html)

Provides Java support using the Java Compiler API.
Requires that you have Java 8 installed on your system.

## Installation for VS Code

[Install from the VS Code marketplace](https://marketplace.visualstudio.com/items?itemName=georgewfraser.vscode-javac)

## Installation and Running for Other Editors (manually)

- clone the repository
- run `mvn package`
- copy the produced jar file at `out/fat-jar.jar` to an appropriate location
- run on a socket with `java -cp -Djavacs.port=${PORT} /path/to/fat-jar.jar org.javacs.Main`
- run through stdin/stdout with `java -cp /path/to/fat-jar.jar org.javacs.Main`

## [Issues](https://github.com/georgewfraser/vscode-javac/issues)

## Features

### Javadoc

<img src="http://g.recordit.co/GROuFBSPQD.gif">

### Signature help

<img src="http://g.recordit.co/pXkdKptzrI.gif">

### Autocomplete symbols (with auto-import)

<img src="http://g.recordit.co/HpNZPIDA8T.gif">

### Autocomplete members

<img src="http://g.recordit.co/np8mXIWfQ8.gif">

### Go-to-definition

<img src="http://g.recordit.co/AJGsEVoF6z.gif">

### Find symbol

<img src="http://g.recordit.co/XuZvrCJfBx.gif">

### Lint

<img src="http://g.recordit.co/Fu8vgP0uG0.gif">

### Type information on hover

<img src="http://g.recordit.co/w5nRIfef65.gif">

### Code actions

<img src="http://g.recordit.co/pjQh1KuyK4.gif">

### Find references

<img src="http://g.recordit.co/3tNYL8StgJ.gif">

## Usage

VSCode will provide autocomplete and help text using:
* .java files anywhere in your workspace
* Java platform classes
* External dependencies specified using `pom.xml`, Bazel, or [settings](#Settings)

## Settings

If VSCode doesn't detect your external dependencies automatically, 
you can specify your external dependencies using [.vscode/settings.json](https://code.visualstudio.com/docs/getstarted/settings)

```json
{
    "java.externalDependencies": [
        "junit:junit:jar:4.12:test", // Maven format
        "junit:junit:4.12" // Gradle-style format is also allowed
    ]
}
```

If all else fails, you can specify the java class path manually:

```json
{
    "java.classPath": [
        "lib/some-dependency.jar"
    ]
}
```

You can generate a list of external dependencies using your build tool:
* Maven: `mvn dependency:list` 
* Gradle: `gradle dependencies`

The Java language server will look for the dependencies you specify in `java.externalDependencies` in your Maven and Gradle caches `~/.m2` and `~/.gradle`.
You should use your build tool to download the library *and* source jars of all your dependencies so that the Java language server can find them:
* Maven
  * `mvn dependency:resolve` for compilation and autocomplete
  * `mvn dependency:resolve -Dclassifier=sources` for inline Javadoc help
* Gradle
  * `gradle dependencies` for compilation and autocomplete
  * Include `classifier: sources` in your build.gradle for inline Javadoc help, for example:
    ```
    dependencies {
        testCompile group: 'junit', name: 'junit', version: '4.+'
        testCompile group: 'junit', name: 'junit', version: '4.+', classifier: 'sources'
    }
    ```

### Optional settings

Optional user settings. These should be set in global settings `Preferences -> Settings`, not in your project directory.

* `java.home` Installation directory of Java 8

## javaconfig.json is depecated

Configuration using a `javaconfig.json` file in your workspace is deprecated; 
please switch to [settings.json](#Settings).

If you have a use case that cannot be supported using `settings.json` please [create an issue](https://github.com/georgewfraser/vscode-javac/issues);

## Directory structure

### Java service process

A java process that does the hard work of parsing and analyzing .java source files.

    pom.xml (maven project file)
    src/ (java sources)
    repo/ (tools.jar packaged in a local maven repo)
    target/ (compiled java .class files, .jar archives)
    target/fat-jar.jar (single jar that needs to be distributed with extension)

### Typescript Visual Studio Code extension

"Glue code" that launches the external java process
and connects to it using [vscode-languageclient](https://www.npmjs.com/package/vscode-languageclient).

    package.json (node package file)
    tsconfig.json (typescript compilation configuration file)
    tsd.json (project file for tsd, a type definitions manager)
    lib/ (typescript sources)
    out/ (compiled javascript)

## Design

This extension consists of an external java process, 
which communicates with vscode using the [language server protocol](https://github.com/Microsoft/vscode-languageserver-protocol). 

### Java service process

The java service process uses the implementation of the Java compiler in tools.jar, 
which is a part of the JDK.
When VS Code needs to lint a file, perform autocomplete, 
or some other task that requires Java code insight,
the java service process invokes the Java compiler programatically,
then intercepts the data structures the Java compiler uses to represent source trees and types.

### Incremental updates

The Java compiler isn't designed for incremental parsing and analysis.
However, it is *extremely* fast, so recompiling a single file gives good performance,
as long as we don't also recompile all of its dependencies.
We cache the .class files that are generated during compilation in a temporary folder,
and use those .class files instead of .java sources whenever they are up-to-date.

## Logs

The java service process will output a log file to stdout, which is visible using View / Output.

## Contributing

If you have npm and maven installed,
you should be able to install locally using 

    npm install -g vsce
    npm install
    ./scripts/install.sh
