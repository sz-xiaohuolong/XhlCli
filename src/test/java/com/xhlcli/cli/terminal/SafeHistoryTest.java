package com.xhlcli.cli.terminal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SafeHistory Tests")
class SafeHistoryTest {

    @Test
    @DisplayName("Should add normal inputs and skip duplicates and blank lines")
    void shouldAddNormalInputsAndSkipDuplicates(@TempDir Path tempDir) {
        Path historyFile = tempDir.resolve("history");
        SafeHistory history = new SafeHistory(historyFile);

        history.add(Instant.now(), "hello world");
        history.add(Instant.now(), "hello world"); // duplicate, skipped
        history.add(Instant.now(), "   "); // blank, skipped
        history.add(Instant.now(), "second turn");

        assertEquals(2, history.size());
        assertEquals("hello world", history.get(0));
        assertEquals("second turn", history.get(1));
    }

    @Test
    @DisplayName("Should filter out sensitive tokens and API keys")
    void shouldFilterSensitiveTokens(@TempDir Path tempDir) {
        Path historyFile = tempDir.resolve("history");
        SafeHistory history = new SafeHistory(historyFile);

        history.add(Instant.now(), "My key is sk-abcdef123456789012345678");
        history.add(Instant.now(), "Bearer ghp_123456789012345678901234567890123456");
        history.add(Instant.now(), "normal question");

        assertEquals(1, history.size());
        assertEquals("normal question", history.get(0));
    }

    @Test
    @DisplayName("Should filter out ultra-long pasted text (> 4000 chars)")
    void shouldFilterUltraLongText(@TempDir Path tempDir) {
        Path historyFile = tempDir.resolve("history");
        SafeHistory history = new SafeHistory(historyFile);

        String hugeText = "x".repeat(4500);
        history.add(Instant.now(), hugeText);
        history.add(Instant.now(), "valid short prompt");

        assertEquals(1, history.size());
        assertEquals("valid short prompt", history.get(0));
    }

    @Test
    @DisplayName("Should list recent entries and support clearing history")
    void shouldListRecentAndClear(@TempDir Path tempDir) throws IOException {
        Path historyFile = tempDir.resolve("history");
        SafeHistory history = new SafeHistory(historyFile);

        history.add(Instant.now(), "cmd1");
        history.add(Instant.now(), "cmd2");
        history.add(Instant.now(), "cmd3");

        List<String> recent = history.listRecent(2);
        assertEquals(2, recent.size());
        assertEquals("cmd2", recent.get(0));
        assertEquals("cmd3", recent.get(1));

        history.clearAll();
        assertEquals(0, history.size());
        assertEquals("", Files.readString(historyFile));
    }

    @Test
    @DisplayName("Should fallback gracefully to in-memory history when file path is invalid")
    void shouldFallbackToMemoryHistory() {
        SafeHistory history = new SafeHistory(null);

        assertTrue(history.isMemoryOnly());
        history.add(Instant.now(), "prompt in memory");
        assertEquals(1, history.size());
        assertEquals("prompt in memory", history.get(0));
    }
}
