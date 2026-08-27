package com.xhlcli.render;

import com.xhlcli.model.RunEvent;
import com.xhlcli.model.RunStatus;
import com.xhlcli.model.TokenUsage;
import com.xhlcli.model.ToolResultStatus;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static RunEvent.Metadata metadata(long sequence) {
        return new RunEvent.Metadata("run-1", sequence, Instant.parse("2026-08-27T00:00:00Z"), 0);
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
