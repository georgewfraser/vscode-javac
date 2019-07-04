package org.javacs;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@Warmup(iterations = 20, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class BenchmarkParser {

    @State(Scope.Benchmark)
    public static class CompilerState {
        public Path file = Paths.get("src/main/java/org/javacs/Artifact.java").normalize();

        @Setup
        public void setup() {
            Profiler.quiet = true;
        }

        @TearDown
        public void teardown() {
            Profiler.quiet = false;
        }
    }

    @Benchmark
    public void parse(CompilerState state) {
        Parser.parseFile(state.file.toUri());
    }

    public static void main(String[] args) {
        var state = new CompilerState();
        state.setup();
        while (true) {
            Parser.parseFile(state.file.toUri());
        }
    }
}
