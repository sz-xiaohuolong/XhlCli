package com.xhlcli.cli;

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

class SnapshotCliTest {

    private final ChatCommandParser parser = new ChatCommandParser();
    private final DefaultParser jlineParser = new DefaultParser();

    @Test
    void parsesSnapshotAndRestoreCommands() {
        assertEquals(ChatCommand.SNAPSHOT, parser.parse("/snapshot"));
        assertEquals(ChatCommand.SNAPSHOT, parser.parse("/snapshot status"));
        assertEquals(ChatCommand.SNAPSHOT, parser.parse("/snapshot clean"));
        assertEquals(ChatCommand.RESTORE, parser.parse("/restore"));
        assertEquals(ChatCommand.RESTORE, parser.parse("/restore 1"));
        assertEquals(ChatCommand.RESTORE, parser.parse("/restore 3"));
    }

    @Test
    void completerProvidesSnapshotAndRestoreCandidates() {
        TerminalCompleter completer = new TerminalCompleter(
                List::of, List::of, List::of, Path.of(".")
        );

        // 1. Primary command completion: /snap -> /snapshot
        ParsedLine line1 = jlineParser.parse("/snap", 5);
        List<Candidate> candidates1 = new ArrayList<>();
        completer.complete(null, line1, candidates1);
        assertTrue(candidates1.stream().anyMatch(c -> c.value().equals("/snapshot")));

        // 2. Primary command completion: /res -> /restore
        ParsedLine line2 = jlineParser.parse("/res", 4);
        List<Candidate> candidates2 = new ArrayList<>();
        completer.complete(null, line2, candidates2);
        assertTrue(candidates2.stream().anyMatch(c -> c.value().equals("/restore")));

        // 3. Subcommand completion for /snapshot
        ParsedLine line3 = jlineParser.parse("/snapshot st", 12);
        List<Candidate> candidates3 = new ArrayList<>();
        completer.complete(null, line3, candidates3);
        assertTrue(candidates3.stream().anyMatch(c -> c.value().equals("status")));
    }
}
