package com.xhlcli.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LongTermMemoryTest {

    @Test
    void readsOldAndExtendedRecordsWhileSkippingBrokenLines(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("long_term.jsonl");
        Files.writeString(file, """
                {"id":"old","content":"使用 Java 21","scope":"project","timestamp":"2026-01-01T00:00:00Z","source":"user"}
                不是 JSON
                null
                {"id":"new","content":"使用 Redis","scope":"project","timestamp":"2026-01-01T00:00:00Z","source":"user","pinned":true,"expiresAt":"2099-01-01T00:00:00Z","supersededBy":null}
                """);
        var entries = new LongTermMemory(file).loadAll();
        assertEquals(List.of("old", "new"), entries.stream().map(MemoryEntry::id).toList());
    }

    @Test
    void testSaveAndLoad(@TempDir Path tempDir) {
        Path memFile = tempDir.resolve("test_memory.jsonl");
        LongTermMemory ltm = new LongTermMemory(memFile);

        MemoryEntry entry1 = MemoryEntry.create("Test memory 1", "global", "user");
        ltm.save(entry1);

        List<MemoryEntry> entries = ltm.loadAll();
        assertEquals(1, entries.size());
        assertEquals("Test memory 1", entries.get(0).content());
        assertEquals("global", entries.get(0).scope());
        
        MemoryEntry entry2 = MemoryEntry.create("Test memory 2", "project", "agent");
        ltm.save(entry2);
        
        entries = ltm.loadAll();
        assertEquals(2, entries.size());
        
        assertTrue(ltm.delete(entry1.id()));
        entries = ltm.loadAll();
        assertEquals(1, entries.size());
        assertEquals("Test memory 2", entries.get(0).content());
        
        ltm.clear();
        assertTrue(ltm.loadAll().isEmpty());
    }
}
