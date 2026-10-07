package com.xhlcli.runtime.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeThreadStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void createThreadAndAppendEvents() throws Exception {
        Path db = tempDir.resolve("threads.db");
        try (RuntimeThreadStore store = new RuntimeThreadStore(db)) {
            String threadId = store.createThread();
            assertNotNull(threadId);
            assertTrue(store.exists(threadId));
            assertFalse(store.exists("non-existent"));

            // Initial event was created
            List<RuntimeEvent> initialEvents = store.events(threadId, 0);
            assertEquals(1, initialEvents.size());
            assertEquals("thread.created", initialEvents.get(0).type());
            long firstEventId = initialEvents.get(0).id();

            // Append additional events
            long e2 = store.appendEvent(threadId, "message.delta", "{\"content\":\"hello\"}");
            long e3 = store.appendEvent(threadId, "turn.completed", "{\"status\":\"ok\"}");
            assertTrue(e2 > firstEventId);
            assertTrue(e3 > e2);

            // Fetch all events
            List<RuntimeEvent> all = store.events(threadId, 0);
            assertEquals(3, all.size());

            // Fetch after cursor
            List<RuntimeEvent> afterFirst = store.events(threadId, firstEventId);
            assertEquals(2, afterFirst.size());
            assertEquals("message.delta", afterFirst.get(0).type());
            assertEquals("turn.completed", afterFirst.get(1).type());

            // Fetch after last cursor
            List<RuntimeEvent> afterLast = store.events(threadId, e3);
            assertTrue(afterLast.isEmpty());
        }
    }
}
