@echo off
set JLINK_VM_OPTIONS=^
--add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED ^
--add-exports jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED ^
--add-exports jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED ^
--add-exports jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED ^
--add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED ^
--add-exports jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED ^
--add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED ^
--add-opens jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED
set CLASSPATH_OPTIONS=-classpath %~dp0/classpath/gson-2.8.5.jar;%~dp0/classpath/protobuf-java-3.9.1.jar;%~dp0/classpath/java-language-server.jar
java %JLINK_VM_OPTIONS% %CLASSPATH_OPTIONS% %*

