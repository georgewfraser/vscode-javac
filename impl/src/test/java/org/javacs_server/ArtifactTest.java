package org.javacs_server;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import org.junit.Test;

public class ArtifactTest {
    @Test
    public void parseShort() {
        assertThat(Artifact.parse("foo:bar:1"), equalTo(new Artifact("foo", "bar", "1")));
    }

    @Test
    public void parseLong() {
        assertThat(Artifact.parse("foo:bar:jar:1:compile"), equalTo(new Artifact("foo", "bar", "1")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void parseIllegal() {
        Artifact.parse("bad");
    }
}
