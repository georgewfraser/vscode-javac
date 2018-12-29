package org.javacs_server;

import static org.hamcrest.Matchers.*;
import static org.javacs_server.TipFormatter.asMarkdown;
import static org.junit.Assert.*;

import org.junit.Test;

public class TipFormatterTest {
    @Test
    public void formatSimpleTags() {
        assertThat(asMarkdown("<i>foo</i>"), equalTo("*foo*"));
        assertThat(asMarkdown("<b>foo</b>"), equalTo("**foo**"));
        assertThat(asMarkdown("<pre>foo</pre>"), equalTo("`foo`"));
        assertThat(asMarkdown("<code>foo</code>"), equalTo("`foo`"));
    }
}
