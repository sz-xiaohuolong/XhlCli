package com.xhlcli.render.terminal;

import com.xhlcli.render.TerminalStatus;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TerminalStatusBar Tests")
class TerminalStatusBarTest {

    private final TerminalStatus testStatus = new TerminalStatus(
            "REAct",
            "Thinking",
            "deepseek-chat",
            4500,
            64000,
            2,
            4,
            "/Users/daiyifei/Documents/code/agent-cli/xhlcli"
    );

    @Test
    @DisplayName("Should format status bar under 80 columns without exceeding width or breaking lines")
    void shouldFormat80Columns() {
        String line = TerminalStatusBar.formatPlain(testStatus, 80);

        assertFalse(line.contains("\n"));
        assertFalse(line.contains("\r"));
        assertTrue(TerminalWidthCalculator.displayWidth(line) <= 80,
                "Width should be <= 80, actual: " + TerminalWidthCalculator.displayWidth(line));
        assertTrue(line.contains("[REAct] [Thinking] deepseek-chat"));
        assertTrue(line.contains("4.5k/64.0k"));
    }

    @Test
    @DisplayName("Should format status bar under 120 columns with MCP and Skill extensions")
    void shouldFormat120Columns() {
        String line = TerminalStatusBar.formatPlain(testStatus, 120);

        assertFalse(line.contains("\n"));
        assertFalse(line.contains("\r"));
        assertTrue(TerminalWidthCalculator.displayWidth(line) <= 120);
        assertTrue(line.contains("[REAct]"));
        assertTrue(line.contains("MCP:2 Skills:4"));
    }

    @Test
    @DisplayName("Should format status bar under 160 columns with full path and all fields")
    void shouldFormat160Columns() {
        String line = TerminalStatusBar.formatPlain(testStatus, 160);

        assertFalse(line.contains("\n"));
        assertFalse(line.contains("\r"));
        assertTrue(TerminalWidthCalculator.displayWidth(line) <= 160);
        assertTrue(line.contains("xhlcli"));
        assertTrue(line.contains("MCP:2 Skills:4"));
    }

    @Test
    @DisplayName("Should aggressively truncate under narrow columns (< 50 columns)")
    void shouldTruncateUnderNarrowColumns() {
        String line = TerminalStatusBar.formatPlain(testStatus, 40);

        assertFalse(line.contains("\n"));
        assertFalse(line.contains("\r"));
        assertTrue(TerminalWidthCalculator.displayWidth(line) <= 40);
        assertTrue(line.endsWith("…") || line.length() <= 40);
    }

    @Test
    @DisplayName("Should produce styled AttributedString correctly")
    void shouldProduceAttributedString() {
        AttributedString as = TerminalStatusBar.formatAttributed(testStatus, 120);
        assertNotNull(as);
        assertTrue(as.columnLength() <= 120);
        assertTrue(as.toString().contains("REAct"));
    }
}
