package org.javacs;

import java.nio.file.Paths;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.openjdk.jmh.annotations.*;

@Warmup(iterations = 20, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class BenchmarkPruner {

    @State(Scope.Benchmark)
    public static class CompilerState {
        public SourceFileObject file = file(false);
        public SourceFileObject pruned = file(true);
        public JavaCompilerService compiler = createCompiler();

        private SourceFileObject file(boolean prune) {
            var file = Paths.get("src/main/java/org/javacs/JavaCompilerService.java").normalize();
            if (prune) {
                var contents = compiler.parseFile(file.toUri()).prune("isWord");
                return new SourceFileObject(file, contents, Instant.now());
            } else {
                return new SourceFileObject(file);
            }
        }

        private static JavaCompilerService createCompiler() {
            LOG.info("Create new compiler...");

            var workspaceRoot = Paths.get(".").normalize().toAbsolutePath();
            FileStore.setWorkspaceRoots(Set.of(workspaceRoot));
            var classPath = new InferConfig(workspaceRoot).classPath();
            return new JavaCompilerService(classPath, Collections.emptySet(), Collections.emptySet());
        }

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
    public void pruned(CompilerState state) {
        state.compiler.compileBatch(List.of(state.pruned)).close();
    }

    @Benchmark
    public void plain(CompilerState state) {
        state.compiler.compileBatch(List.of(state.file)).close();
    }

    private static final Logger LOG = Logger.getLogger("main");
}
