#!/bin/bash
# Patch Gson with module-info.java
# When Gson releases a new version, we will no longer need to do this 
# https://github.com/google/gson/issues/1315

set -e

# Check JAVA_HOME points to java 11
./scripts/check_java_home.sh

# Download Gson jar
cd modules
curl https://repo1.maven.org/maven2/com/google/code/gson/gson/2.8.5/gson-2.8.5.jar > gson.jar

# Unpack jar into modules/classes
mkdir classes
cd classes
$JAVA_HOME/bin/jar xf ../gson.jar
cd ..

# Compile module-info.java to module-info.class
$JAVA_HOME/bin/javac -p com.google.gson -d classes module-info.java

# Update gson.jar with module-info.class
$JAVA_HOME/bin/jar uf gson.jar -C classes module-info.class

# Clean up
rm -rf classes
cd ..