
#!/bin/bash

# Check JAVA_HOME points to correct java version
./scripts/check_java_home.sh

# Compile the benchmark
mvn test-compile

# Emit the dependencies classpath
mvn dependency:build-classpath -DincludeScope=test -Dmdep.outputFile=scripts/classpath.txt

# Run the benchmark
java -cp $(cat scripts/classpath.txt):target/classes:target/test-classes --illegal-access=warn org.openjdk.jmh.Main BenchmarkPruner

# Clean up
rm scripts/classpath.txt