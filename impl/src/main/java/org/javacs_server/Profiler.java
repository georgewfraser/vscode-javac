package org.javacs_server;

import com.sun.source.util.*;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

class Profiler implements TaskListener {
    Set<URI> files = new HashSet<>();
    Map<TaskEvent.Kind, Instant> started = new EnumMap<>(TaskEvent.Kind.class);
    Map<TaskEvent.Kind, Duration> profile = new EnumMap<>(TaskEvent.Kind.class);

    @Override
    public void started(TaskEvent e) {
        started.put(e.getKind(), Instant.now());
        files.add(e.getSourceFile().toUri());
    }

    @Override
    public void finished(TaskEvent e) {
        var k = e.getKind();
        var start = started.getOrDefault(k, Instant.now());
        var elapsed = Duration.between(start, Instant.now());
        var soFar = profile.getOrDefault(k, Duration.ZERO);
        var total = soFar.plus(elapsed);
        profile.put(k, total);
    }

    void print() {
        var lines = new StringJoiner("; ");
        for (var k : TaskEvent.Kind.values()) {
            if (!profile.containsKey(k)) continue;
            var elapsed = profile.get(k);
            var s = elapsed.getSeconds() + elapsed.getNano() / 1000.0 / 1000.0 / 1000.0;
            lines.add(String.format("%s: %.3fs", k, s));
        }
        LOG.info(String.format("Compiled %d files: %s", files.size(), lines));
    }

    private static final Logger LOG = Logger.getLogger("main");
}
