package com.xhlcli.render.format;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ToolFoldingManager Tests")
class ToolFoldingManagerTest {

    @Test
    @DisplayName("Should not fold short outputs under line and character threshold")
    void shouldNotFoldShortOutput() {
        String shortOutput = "line 1\nline 2\nline 3";
        var res = ToolFoldingManager.process(shortOutput, 5, 200);

        assertFalse(res.folded());
        assertEquals(shortOutput, res.displayContent());
        assertEquals(3, res.totalLines());
    }

    @Test
    @DisplayName("Should fold long outputs exceeding line limit and display preview with omitted count")
    void shouldFoldLongLinesOutput() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            sb.append("File content line ").append(i).append("\n");
        }
        var res = ToolFoldingManager.process(sb.toString(), 5, 500);

        assertTrue(res.folded());
        assertEquals(20, res.totalLines());
        assertTrue(res.displayContent().contains("File content line 1"));
        assertTrue(res.displayContent().contains("... [17 lines"));
    }

    @Test
    @DisplayName("Should fold outputs exceeding character limit")
    void shouldFoldLongCharacterOutput() {
        String longText = "a".repeat(400);
        var res = ToolFoldingManager.process(longText, 10, 200);

        assertTrue(res.folded());
        assertTrue(res.displayContent().contains("omitted"));
    }
}
