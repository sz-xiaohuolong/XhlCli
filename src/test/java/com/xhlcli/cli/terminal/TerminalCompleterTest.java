package com.xhlcli.cli.terminal;

import org.jline.reader.Candidate;
import org.jline.reader.ParsedLine;
import org.jline.reader.impl.DefaultParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TerminalCompleter Tests")
class TerminalCompleterTest {

    private final DefaultParser parser = new DefaultParser();

    @Test
    @DisplayName("Should complete primary slash commands with prefix match")
    void shouldCompletePrimaryCommands() {
        TerminalCompleter completer = new TerminalCompleter(
                List::of, List::of, List::of, null
        );

        List<Candidate> candidates = new ArrayList<>();
        ParsedLine line = parser.parse("/m", 2);
        completer.complete(null, line, candidates);

        List<String> values = candidates.stream().map(Candidate::value).toList();
        assertTrue(values.contains("/model"));
        assertTrue(values.contains("/mcp"));
        assertTrue(values.contains("/memory"));
        assertFalse(values.contains("/help"));
    }

    @Test
    @DisplayName("Should complete subcommands for /model")
    void shouldCompleteSubcommands() {
        TerminalCompleter completer = new TerminalCompleter(
                List::of, List::of, List::of, null
        );

        List<Candidate> candidates = new ArrayList<>();
        ParsedLine line = parser.parse("/model ", 7, org.jline.reader.Parser.ParseContext.COMPLETE);
        completer.complete(null, line, candidates);

        List<String> values = candidates.stream().map(Candidate::value).toList();
        assertTrue(values.contains("list"));
        assertTrue(values.contains("use"));
        assertTrue(values.contains("status"));
    }

    @Test
    @DisplayName("Should complete third-level dynamic models for /model use")
    void shouldCompleteDynamicModels() {
        TerminalCompleter completer = new TerminalCompleter(
                () -> List.of("deepseek-chat", "gpt-4o", "claude-3-5-sonnet"),
                List::of,
                List::of,
                null
        );

        List<Candidate> candidates = new ArrayList<>();
        ParsedLine line = parser.parse("/model use deep", 15);
        completer.complete(null, line, candidates);

        List<String> values = candidates.stream().map(Candidate::value).toList();
        assertEquals(1, values.size());
        assertEquals("deepseek-chat", values.get(0));
    }

    @Test
    @DisplayName("Should complete project relative paths with @ prefix")
    void shouldCompleteProjectRelativePaths(@TempDir Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve("src"));
        Files.createFile(tempDir.resolve("src").resolve("Main.java"));
        Files.createFile(tempDir.resolve("README.md"));

        TerminalCompleter completer = new TerminalCompleter(
                List::of, List::of, List::of, tempDir
        );

        List<Candidate> candidates = new ArrayList<>();
        ParsedLine line = parser.parse("@src/", 5);
        completer.complete(null, line, candidates);

        List<String> values = candidates.stream().map(Candidate::value).toList();
        assertTrue(values.contains("@src/Main.java"));
        assertFalse(values.contains("@README.md"));
    }
}
