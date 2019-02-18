#!/bin/bash

set -e

# Check JAVA_HOME points to correct java version
./scripts/check_java_home.sh

mvn test -Dtest=$1#$2