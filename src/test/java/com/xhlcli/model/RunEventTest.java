package com.xhlcli.model;

import com.xhlcli.llm.LlmErrorType;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunEventTest {
    private static final RunEvent.Metadata METADATA = new RunEvent.Metadata(
            "run_1", 1, Instant.parse("2026-08-27T00:00:00Z"), 0);

    @Test
    void metadataRejectsBlankRunIdsAndNonPositiveSequences() {
        assertThrows(IllegalArgumentException.class,
                () -> new RunEvent.Metadata(" ", 1, Instant.EPOCH, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new RunEvent.Metadata("run_1", 0, Instant.EPOCH, 0));
    }

    @Test
    void terminalEventsExposeTheirExpectedRunStatuses() {
        assertEquals(RunStatus.COMPLETED,
                new RunEvent.RunCompleted(METADATA, "done", TokenUsage.unknown()).status());
        assertEquals(RunStatus.FAILED,
                new RunEvent.RunFailed(METADATA, "PROTOCOL").status());
        assertEquals(RunStatus.CANCELED,
                new RunEvent.RunCancelled(METADATA, "USER").status());
        assertEquals(RunStatus.LIMIT_REACHED,
                new RunEvent.RunLimitReached(METADATA, "ITERATIONS").status());
    }

    @Test
    void failedEventsRetainOptionalStructuredLlmFailureDetails() {
        RunEvent.RunFailed failure = new RunEvent.RunFailed(
                METADATA, "NETWORK", LlmErrorType.NETWORK, "Provider connection failed.", true);

        assertEquals(LlmErrorType.NETWORK, failure.errorType());
        assertEquals("Provider connection failed.", failure.safeMessage());
        assertEquals(true, failure.partialResponse());
        assertEquals(null, new RunEvent.RunFailed(METADATA, "PROTOCOL").errorType());
        assertThrows(IllegalArgumentException.class,
                () -> new RunEvent.RunFailed(METADATA, "PROTOCOL", null, "not an LLM failure", false));
        assertThrows(IllegalArgumentException.class,
                () -> new RunEvent.RunFailed(METADATA, "NETWORK", LlmErrorType.NETWORK, " ", false));
    }
}
