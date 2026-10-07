package com.xhlcli.runtime.task;

import com.xhlcli.cli.ChatCommand;
import com.xhlcli.cli.ChatCommandParser;
import com.xhlcli.cli.terminal.TerminalCompleter;
import org.jline.reader.Candidate;
import org.jline.reader.ParsedLine;
import org.jline.reader.impl.DefaultParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskCliTest {

    private final ChatCommandParser parser = new ChatCommandParser();
    private final DefaultParser jlineParser = new DefaultParser();

    @Test
    void shouldParseTaskCommands() {
        assertEquals(ChatCommand.TASK, parser.parse("/task"));
        assertEquals(ChatCommand.TASK, parser.parse("/task list"));
        assertEquals(ChatCommand.TASK, parser.parse("/task add 跑自动化脚本"));
        assertEquals(ChatCommand.TASK, parser.parse("/task log task_123"));
        assertEquals(ChatCommand.TASK, parser.parse("/task cancel task_123"));
    }

    @Test
    void shouldCompletePrimaryTaskCommand() {
        TerminalCompleter completer = new TerminalCompleter(
                List::of, List::of, List::of, Path.of(".")
        );

        ParsedLine line = jlineParser.parse("/tas", 4);
        List<Candidate> candidates = new ArrayList<>();
        completer.complete(null, line, candidates);

        assertTrue(candidates.stream().anyMatch(c -> c.value().equals("/task")));
    }

    @Test
    void shouldCompleteTaskSubcommands() {
        TerminalCompleter completer = new TerminalCompleter(
                List::of, List::of, List::of, Path.of(".")
        );

        ParsedLine line = jlineParser.parse("/task l", 7);
        List<Candidate> candidates = new ArrayList<>();
        completer.complete(null, line, candidates);

        List<String> values = candidates.stream().map(Candidate::value).toList();
        assertTrue(values.contains("list"));
        assertTrue(values.contains("log"));
    }
}
