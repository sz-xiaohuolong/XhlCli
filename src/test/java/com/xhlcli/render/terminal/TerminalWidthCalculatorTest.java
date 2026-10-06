package com.xhlcli.render.terminal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TerminalWidthCalculator Tests")
class TerminalWidthCalculatorTest {

    @Test
    @DisplayName("Should accurately calculate ASCII string width")
    void shouldCalculateAsciiWidth() {
        assertEquals(5, TerminalWidthCalculator.displayWidth("hello"));
        assertEquals(11, TerminalWidthCalculator.displayWidth("hello world"));
    }

    @Test
    @DisplayName("Should accurately calculate CJK full-width characters as 2 columns each")
    void shouldCalculateCjkWidth() {
        // "你好世界" = 4 characters, 8 visual columns
        assertEquals(8, TerminalWidthCalculator.displayWidth("你好世界"));
        // "Agent助手" = 5 (Agent) + 4 (助手) = 9 columns
        assertEquals(9, TerminalWidthCalculator.displayWidth("Agent助手"));
    }

    @Test
    @DisplayName("Should strip ANSI sequences before calculating display width")
    void shouldIgnoreAnsiCodesInWidth() {
        String colored = "\u001B[31mRed\u001B[0m text \u001B[1;32mGreen\u001B[0m";
        // Clean text: "Red text Green" = 14 columns
        assertEquals(14, TerminalWidthCalculator.displayWidth(colored));
    }

    @Test
    @DisplayName("Should truncate string accurately to max width with ellipsis")
    void shouldTruncateToWidth() {
        String result = TerminalWidthCalculator.truncateToWidth("HelloWorldLongText", 10, "…");
        assertTrue(TerminalWidthCalculator.displayWidth(result) <= 10);
        assertTrue(result.endsWith("…"));

        String cjkResult = TerminalWidthCalculator.truncateToWidth("你好世界深度学习人工智能", 10, "…");
        assertTrue(TerminalWidthCalculator.displayWidth(cjkResult) <= 10);
        assertTrue(cjkResult.endsWith("…"));
    }

    @Test
    @DisplayName("Should pad string to target width correctly")
    void shouldPadRight() {
        String padded = TerminalWidthCalculator.padRight("test", 10);
        assertEquals(10, TerminalWidthCalculator.displayWidth(padded));
        assertEquals("test      ", padded);
    }
}
