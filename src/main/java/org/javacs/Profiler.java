package org.javacs;

import com.sun.source.util.*;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

class Profiler implements TaskListener {
    static boolean quiet = false;

    Set<URI> files = new HashSet<>();

    TaskEvent current;
    Instant started = Instant.EPOCH;
    Map<TaskEvent.Kind, Duration> profile = new EnumMap<>(TaskEvent.Kind.class);

    @Override
    public void started(TaskEvent e) {
        files.add(e.getSourceFile().toUri());
        endCurrent();
        current = e;
        started = Instant.now();
    }

    @Override
    public void finished(TaskEvent e) {
        endCurrent();
    }

    private void endCurrent() {
        if (current == null) return;
        var soFar = profile.getOrDefault(current.getKind(), Duration.ZERO);
        var add = Duration.between(started, Instant.now());
        var total = soFar.plus(add);
        profile.put(current.getKind(), total);
        current = null;
        started = Instant.EPOCH;
    }

    void print() {
        if (quiet) return;
        var lines = new StringJoiner("; ");
        for (var kind : TaskEvent.Kind.values()) {
            if (!profile.containsKey(kind)) continue;
            var elapsed = profile.get(kind);
            var s = elapsed.getSeconds() + elapsed.getNano() / 10e9;
            lines.add(String.format("%s: %.3fs", kind, s));
        }
        LOG.info(String.format("...compiled %s: %s", describe(files), lines));
    }

    static String describe(Collection<URI> files) {
        if (files.size() <= 3) {
            var names = new StringJoiner(", ");
            for (var f : files) {
                names.add(StringSearch.fileName(f));
            }
            return names.toString();
        } else {
            return files.size() + " files";
        }
    }

    private static final Logger LOG = Logger.getLogger("main");
}
