package org.javacs;

import java.nio.file.Paths;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.javacs.completion.PruneMethodBodies;
import org.openjdk.jmh.annotations.*;
import org.javacs.config.InferConfig;

@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class BenchmarkPruner {

    @State(Scope.Benchmark)
    public static class CompilerState {
        public SourceFileObject file = file(false);
        public SourceFileObject pruned = file(true);
        public JavaCompilerService compiler = createCompiler();

        private SourceFileObject file(boolean prune) {
            var file = Paths.get("src/main/java/org/javacs/config/InferConfig.java").normalize();
            if (prune) {
                var task = compiler.parse(file);
                var contents = new PruneMethodBodies(task.task).scan(task.root, 11222L).toString();
                return new SourceFileObject(file, contents, Instant.now());
            } else {
                return new SourceFileObject(file);
            }
        }

        private static JavaCompilerService createCompiler() {
            LOG.info("Create new compiler...");

            var workspaceRoot = Paths.get(".").normalize().toAbsolutePath();
            FileStore.setWorkspaceRoots(Set.of(workspaceRoot));
            var classPath = new InferConfig(workspaceRoot).classpath();
            return new JavaCompilerService(classPath, Collections.emptySet(), Collections.emptySet());
        }
    }

    @Benchmark
    public void parsePlain(CompilerState state) {
        Parser.parseJavaFileObject(state.file);
    }

    @Benchmark
    public void compilePruned(CompilerState state) {
        state.compiler.compile(List.of(state.pruned)).close();
    }

    @Benchmark
    public void compilePlain(CompilerState state) {
        state.compiler.compile(List.of(state.file)).close();
    }

    private static final Logger LOG = Logger.getLogger("main");
}
