package com.xhlcli.runtime.task;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurableTaskTest {

    @Test
    void terminalStatusCheck() {
        DurableTask enqueued = new DurableTask("t1", TaskStatus.ENQUEUED, "hello", "/workspace", null, null,
                Instant.now(), null, null, 0);
        assertFalse(enqueued.terminal());

        DurableTask running = new DurableTask("t2", TaskStatus.RUNNING, "hello", "/workspace", null, null,
                Instant.now(), Instant.now(), null, 0);
        assertFalse(running.terminal());

        DurableTask waiting = new DurableTask("t3", TaskStatus.WAITING_FOR_APPROVAL, "hello", "/workspace", null, null,
                Instant.now(), Instant.now(), null, 0);
        assertFalse(waiting.terminal());

        DurableTask completed = new DurableTask("t4", TaskStatus.COMPLETED, "hello", "/workspace", "ok", null,
                Instant.now(), Instant.now(), Instant.now(), 120);
        assertTrue(completed.terminal());

        DurableTask failed = new DurableTask("t5", TaskStatus.FAILED, "hello", "/workspace", null, "err",
                Instant.now(), Instant.now(), Instant.now(), 50);
        assertTrue(failed.terminal());

        DurableTask canceled = new DurableTask("t6", TaskStatus.CANCELED, "hello", "/workspace", null, "canceled",
                Instant.now(), Instant.now(), Instant.now(), 30);
        assertTrue(canceled.terminal());
    }

    @Test
    void shortPromptFormatting() {
        DurableTask taskShort = new DurableTask("t1", TaskStatus.ENQUEUED, "hello world\nline 2", "/workspace", null, null,
                Instant.now(), null, null, 0);
        assertEquals("hello world line 2", taskShort.shortPrompt());

        String longText = "a".repeat(120);
        DurableTask taskLong = new DurableTask("t2", TaskStatus.ENQUEUED, longText, "/workspace", null, null,
                Instant.now(), null, null, 0);
        assertEquals("a".repeat(80) + "...", taskLong.shortPrompt());

        DurableTask taskNull = new DurableTask("t3", TaskStatus.ENQUEUED, null, "/workspace", null, null,
                Instant.now(), null, null, 0);
        assertEquals("", taskNull.shortPrompt());
    }
}
