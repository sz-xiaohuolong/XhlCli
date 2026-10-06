package com.xhlcli.render.format;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DiffRenderer Tests")
class DiffRendererTest {

    private final String sampleDiff = """
            diff --git a/src/App.java b/src/App.java
            index 83a1..92b3 100644
            --- a/src/App.java
            +++ b/src/App.java
            @@ -1,4 +1,5 @@
             package com.demo;
            -public class OldApp {
            +public class NewApp {
            +    // added comment
             }
            """;

    @Test
    @DisplayName("Should detect unified diff format")
    void shouldDetectDiff() {
        assertTrue(DiffRenderer.isDiff(sampleDiff));
        assertFalse(DiffRenderer.isDiff("Just a plain text message."));
    }

    @Test
    @DisplayName("Should analyze diff statistics accurately")
    void shouldAnalyzeDiff() {
        DiffRenderer.DiffStats stats = DiffRenderer.analyze(sampleDiff);

        assertEquals("src/App.java", stats.filePath());
        assertEquals(2, stats.addedLines());
        assertEquals(1, stats.deletedLines());
    }

    @Test
    @DisplayName("Should render colored diff with green added and red deleted lines")
    void shouldRenderColoredDiff() {
        String colored = DiffRenderer.renderColored(sampleDiff);
        assertTrue(colored.contains("📄 File: src/App.java"));
        assertTrue(colored.contains("(+2)"));
        assertTrue(colored.contains("(-1)"));
        assertTrue(colored.contains("\u001B[32m+public class NewApp"));
        assertTrue(colored.contains("\u001B[31m-public class OldApp"));
        assertTrue(colored.contains("\u001B[33m@@ -1,4 +1,5 @@"));
    }

    @Test
    @DisplayName("Should render plain diff without ANSI codes")
    void shouldRenderPlainDiff() {
        String plain = DiffRenderer.renderPlain(sampleDiff);

        assertTrue(plain.contains("File: src/App.java (+2, -1)"));
        assertFalse(plain.contains("\u001B["));
    }
}
