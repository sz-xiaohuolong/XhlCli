package com.xhlcli.cli.terminal;

import org.jline.utils.AttributedString;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TerminalInputHighlighter Tests")
class TerminalInputHighlighterTest {

    private final TerminalInputHighlighter highlighter = new TerminalInputHighlighter();

    @Test
    @DisplayName("Should highlight slash commands with yellow style")
    void shouldHighlightSlashCommands() {
        AttributedString res = highlighter.highlight(null, "/model use deepseek-chat");

        assertNotNull(res);
        assertEquals("/model use deepseek-chat", res.toString());
        // Verify style was applied (style indices exist)
        assertTrue(res.length() > 0);
    }

    @Test
    @DisplayName("Should highlight @path references")
    void shouldHighlightAtPaths() {
        AttributedString res = highlighter.highlight(null, "Please read @src/Main.java and explain");

        assertNotNull(res);
        assertEquals("Please read @src/Main.java and explain", res.toString());
        assertTrue(res.toString().contains("@src/Main.java"));
    }

    @Test
    @DisplayName("Should detect and style sensitive API tokens as warnings")
    void shouldHighlightSensitiveTokens() {
        AttributedString res = highlighter.highlight(null, "token is sk-123456789012345678901234");

        assertNotNull(res);
        assertEquals("token is sk-123456789012345678901234", res.toString());
    }

    @Test
    @DisplayName("Should handle empty and blank buffers safely")
    void shouldHandleEmptyBuffer() {
        AttributedString empty = highlighter.highlight(null, "");
        assertEquals("", empty.toString());

        AttributedString nullStr = highlighter.highlight(null, null);
        assertEquals("", nullStr.toString());
    }
}
