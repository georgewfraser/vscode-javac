#!/bin/bash

set -e

# Check JAVA_HOME points to correct java version
./scripts/check_java_home.sh

# Needed once
npm install

# Build fat jar
./scripts/link_mac.sh
./scripts/link_windows.sh

# Build vsix
vsce package -o build.vsix

code --install-extension build.vsix

echo 'Reload VSCode to update extension'
