package org.javacs_server;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.stream.Collectors;
import org.junit.Test;

public class PrunerTest {

    private String contents(String resourceFile) {
        try (var in = JavaCompilerServiceTest.class.getResourceAsStream(resourceFile)) {
            return new BufferedReader(new InputStreamReader(in)).lines().collect(Collectors.joining("\n"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void pruneMethods() {
        var actual = new Pruner(URI.create("/PruneMethods.java"), contents("/PruneMethods.java")).prune(6, 19);
        var expected = contents("/PruneMethods_erased.java");
        assertThat(actual, equalToIgnoringWhiteSpace(expected));
    }

    @Test
    public void pruneToEndOfBlock() {
        var actual =
                new Pruner(URI.create("/PruneToEndOfBlock.java"), contents("/PruneToEndOfBlock.java")).prune(4, 18);
        var expected = contents("/PruneToEndOfBlock_erased.java");
        assertThat(actual, equalToIgnoringWhiteSpace(expected));
    }

    @Test
    public void pruneMiddle() {
        var actual = new Pruner(URI.create("/PruneMiddle.java"), contents("/PruneMiddle.java")).prune(4, 12);
        var expected = contents("/PruneMiddle_erased.java");
        assertThat(actual, equalToIgnoringWhiteSpace(expected));
    }

    @Test
    public void pruneDot() {
        var actual = new Pruner(URI.create("/PruneDot.java"), contents("/PruneDot.java")).prune(3, 11);
        var expected = contents("/PruneDot_erased.java");
        assertThat(actual, equalToIgnoringWhiteSpace(expected));
    }
}
