#!/bin/bash
# Check the version of java pointed to by JAVA_HOME in version 11.

set -e

if [[ -z "${JAVA_HOME}" ]]; then
  echo "JAVA_HOME must be set"
  exit 1
fi


if [ ! -f "$JAVA_HOME/bin/java" ]; then
  echo "JAVA_HOME is set to: $JAVA_HOME"
  echo "JAVA_HOME does not point to an installation of Java"
  exit 1
fi

java_version=$("$JAVA_HOME/bin/java" -version 2>&1 | sed -n ';s/.* version "\(.*\)\.\(.*\)\..*".*/\1/p;')
if [ "$java_version" -lt 11 ]; then
  echo "JAVA_HOME is set to: $JAVA_HOME"
  echo "JAVA_HOME version is: $java_version"
  echo "JAVA_HOME must be set to a JDK version >=11"
  exit 1
fi
