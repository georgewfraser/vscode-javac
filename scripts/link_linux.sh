#!/bin/bash
# Create self-contained copy of java in dist/linux

set -e

# Set env variables to build with mac toolchain but linux target
REAL_JAVA_HOME=$JAVA_HOME
JAVA_HOME="./jdks/linux/jdk-13"

# Build in dist/linux
rm -rf dist/linux
$REAL_JAVA_HOME/bin/jlink \
  --module-path $JAVA_HOME/jmods \
  --add-modules java.base,java.compiler,java.logging,java.naming,java.sql,java.xml,jdk.compiler,jdk.jdi,jdk.unsupported,jdk.zipfs \
  --output dist/linux \
  --no-header-files \
  --no-man-pages \
  --compress 2
