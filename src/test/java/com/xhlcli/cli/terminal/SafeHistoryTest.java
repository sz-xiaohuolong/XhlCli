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
    @DisplayName("重启后读取安全输入，每条记录只追加一次")
    void restoresPersistedInputsWithoutDuplicatingEarlierEntries(@TempDir Path root) throws IOException {
        Path path = root.resolve("history");
        SafeHistory first = new SafeHistory(path);
        try (var terminal = attach(first)) {
            first.add(Instant.now(), "自动团队验收");
            first.add(Instant.now(), "追问成果");
        }
        SafeHistory second = new SafeHistory(path);
        try (var terminal = attach(second)) {
            assertFalse(second.isMemoryOnly());
            assertEquals(List.of("自动团队验收", "追问成果"), second.listRecent(20));
            second.add(Instant.now(), "重启后的输入");
        }
        assertEquals(3, Files.readAllLines(path).size());
        SafeHistory third = new SafeHistory(path);
        try (var terminal = attach(third)) { assertEquals(3, third.size()); }
    }

    private static org.jline.terminal.Terminal attach(SafeHistory history) throws IOException {
        var terminal = new org.jline.terminal.impl.DumbTerminal(
                new java.io.ByteArrayInputStream(new byte[0]), new java.io.ByteArrayOutputStream());
        var reader = org.jline.reader.LineReaderBuilder.builder().terminal(terminal).history(history).build();
        history.attach(reader);
        return terminal;
    }

    @Test
    @DisplayName("恢复超过内存上限的历史后仍能读取最近二十条")
    void listsRecentEntriesAfterRestoringLongHistory(@TempDir Path root) throws IOException {
        Path path = root.resolve("history");
        var lines = new java.util.ArrayList<String>();
        for (int i = 0; i < 1000; i++) lines.add((1700000000000L + i) + ":输入" + i);
        Files.write(path, lines);
        SafeHistory history = new SafeHistory(path);
        try (var terminal = attach(history)) {
            var recent = history.listRecent(20);
            assertEquals(20, recent.size());
            assertEquals("输入980", recent.getFirst());
            assertEquals("输入999", recent.getLast());
        }
    }

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
