#!/bin/bash
# Links everything into a self-contained executable using jlink.

set -e

# Needed if you have a java version other than 11 as default
JAVA_HOME=$(/usr/libexec/java_home -v 11)

# Compile sources
mvn compile

# Patch gson
if [ ! -e modules/gson.jar ]; then
  ./scripts/patch_gson.sh
fi

# Build using jlink
rm -rf dist/mac
$JAVA_HOME/bin/jlink \
  --module-path modules/gson.jar:target/classes \
  --add-modules gson,javacs \
  --launcher launcher=javacs/org.javacs_server.Main \
  --output dist/mac \
  --compress 2 