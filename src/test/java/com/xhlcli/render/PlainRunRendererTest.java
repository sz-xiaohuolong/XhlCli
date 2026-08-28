package com.xhlcli.render;

import com.xhlcli.model.RunEvent;
import com.xhlcli.model.RunStatus;
import com.xhlcli.model.TokenUsage;
import com.xhlcli.model.ToolResultStatus;
import com.xhlcli.llm.LlmErrorType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlainRunRendererTest {

    @Test
    void rendersSafeObservableTimelineWithoutAnsiOrSecrets() {
        Streams streams = new Streams("top-secret-key");
        RunEvent.Metadata first = metadata(1);

        streams.renderer.accept(new RunEvent.ModelRequestStarted(first));
        streams.renderer.accept(new RunEvent.TextDelta(metadata(2), "hello "));
        streams.renderer.accept(new RunEvent.TextDelta(metadata(3), "world"));
        streams.renderer.accept(new RunEvent.ToolStarted(metadata(4), "echo_text",
                "{\"token\":\"top-secret-key\",\"text\":\"" + "x".repeat(240) + "\"}"));
        streams.renderer.accept(new RunEvent.ToolCompleted(metadata(5), "echo_text",
                ToolResultStatus.SUCCESS, 12, "Echoed top-secret-key."));
        streams.renderer.accept(new RunEvent.RunCompleted(metadata(6), "done", TokenUsage.unknown()));

        String rendered = streams.out();
        assertTrue(rendered.contains("Thinking..."));
        assertTrue(rendered.indexOf("Thinking...") == rendered.lastIndexOf("Thinking..."));
        assertTrue(rendered.contains("Assistant: hello world"));
        assertTrue(rendered.contains("Tool echo_text:"));
        assertTrue(rendered.contains("SUCCESS"));
        assertTrue(rendered.contains("12ms"));
        assertFalse(rendered.contains("top-secret-key"));
        assertFalse(rendered.contains("\u001B["));
    }

    @Test
    void distinguishesEachTerminalState() {
        Streams streams = new Streams(null);

        streams.renderer.accept(new RunEvent.RunFailed(metadata(1), "NETWORK"));
        streams.renderer.accept(new RunEvent.RunCancelled(metadata(2), "USER_CANCELED"));
        streams.renderer.accept(new RunEvent.RunLimitReached(metadata(3), "MAX_ITERATIONS"));

        String rendered = streams.err();
        assertTrue(rendered.contains(RunStatus.FAILED.name()));
        assertTrue(rendered.contains(RunStatus.CANCELED.name()));
        assertTrue(rendered.contains(RunStatus.LIMIT_REACHED.name()));
    }

    @Test
    void showsThinkingAndAssistantPrefixesOncePerRunAcrossToolAndFinalModelRounds() {
        Streams streams = new Streams("top-secret-key");

        streams.renderer.accept(new RunEvent.RunStarted(metadata("run-1", 1), "first"));
        streams.renderer.accept(new RunEvent.ModelRequestStarted(metadata("run-1", 2)));
        streams.renderer.accept(new RunEvent.TextDelta(metadata("run-1", 3), "planning"));
        streams.renderer.accept(new RunEvent.ToolStarted(metadata("run-1", 4), "echo_text", "{}"));
        streams.renderer.accept(new RunEvent.ToolCompleted(metadata("run-1", 5), "echo_text",
                ToolResultStatus.SUCCESS, 1, "ok"));
        streams.renderer.accept(new RunEvent.ModelRequestStarted(metadata("run-1", 6)));
        streams.renderer.accept(new RunEvent.TextDelta(metadata("run-1", 7), "final"));
        streams.renderer.accept(new RunEvent.RunCompleted(metadata("run-1", 8), "final", TokenUsage.unknown()));

        streams.renderer.accept(new RunEvent.RunStarted(metadata("run-2", 1), "second"));
        streams.renderer.accept(new RunEvent.ModelRequestStarted(metadata("run-2", 2)));
        streams.renderer.accept(new RunEvent.TextDelta(metadata("run-2", 3), "new run"));
        streams.renderer.accept(new RunEvent.RunCompleted(metadata("run-2", 4), "new run", TokenUsage.unknown()));

        String rendered = streams.out();
        assertEquals(2, occurrences(rendered, "Thinking..."));
        assertEquals(2, occurrences(rendered, "Assistant: "));
        assertTrue(rendered.contains("planning\nTool echo_text:"));
        assertTrue(rendered.contains("final\n[tokens: unknown]"));
    }

    @Test
    void rendersEveryTypedLlmFailureWithPhaseOneGuidanceAndPartialMarker() {
        Map<LlmErrorType, String> suggestions = Map.ofEntries(
                Map.entry(LlmErrorType.MISSING_CONFIGURATION, "Set DEEPSEEK_API_KEY in the project .env file."),
                Map.entry(LlmErrorType.AUTHENTICATION, "Check DEEPSEEK_API_KEY and its account permissions."),
                Map.entry(LlmErrorType.RATE_LIMIT, "Wait briefly, then try again."),
                Map.entry(LlmErrorType.NETWORK, "Check your network connection and DeepSeek endpoint."),
                Map.entry(LlmErrorType.SERVER, "The provider is unavailable; try again later."),
                Map.entry(LlmErrorType.INVALID_RESPONSE, "Retry the request; use DEBUG logs if the problem persists."),
                Map.entry(LlmErrorType.TIMEOUT, "Increase the timeout or retry on a stable connection."),
                Map.entry(LlmErrorType.CANCELLED, "Submit a new prompt when ready."),
                Map.entry(LlmErrorType.INVALID_CONFIGURATION, "Check the configured model, URL, and timeout values."),
                Map.entry(LlmErrorType.EMPTY_RESPONSE, "Retry the request; use DEBUG logs if the problem persists."));

        for (Map.Entry<LlmErrorType, String> entry : suggestions.entrySet()) {
            Streams streams = new Streams("top-secret-key");
            streams.renderer.accept(new RunEvent.RunFailed(
                    metadata(1), entry.getKey().name(), entry.getKey(), "provider rejected top-secret-key", true));

            String rendered = streams.err();
            assertTrue(rendered.contains("[" + entry.getKey() + "]"));
            assertTrue(rendered.contains("(response incomplete)"));
            assertTrue(rendered.contains(entry.getValue()));
            assertFalse(rendered.contains("top-secret-key"));
            assertFalse(rendered.contains("\u001B["));
        }
    }

    private static RunEvent.Metadata metadata(long sequence) {
        return metadata("run-1", sequence);
    }

    private static RunEvent.Metadata metadata(String runId, long sequence) {
        return new RunEvent.Metadata(runId, sequence, Instant.parse("2026-08-27T00:00:00Z"), 0);
    }

    private static int occurrences(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    private static final class Streams {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final ByteArrayOutputStream err = new ByteArrayOutputStream();
        private final PlainRunRenderer renderer = new PlainRunRenderer(
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), "top-secret-key");

        private Streams(String ignored) {}

        private String out() {
            return out.toString(StandardCharsets.UTF_8);
        }

        private String err() {
            return err.toString(StandardCharsets.UTF_8);
        }
    }
}
