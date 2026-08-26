package com.xhlcli.render;

import com.xhlcli.config.ChatConfig;
import com.xhlcli.config.LogLevel;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlainDiagnosticSinkTest {

    @Test
    void debugPrintsOnlySanitizedStructuredMetadata() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PlainDiagnosticSink sink = new PlainDiagnosticSink(
                config(LogLevel.DEBUG), new PrintStream(bytes, true, StandardCharsets.UTF_8));

        sink.debug("request.retry", Map.of(
                "provider", "deepseek",
                "model", "test-model",
                "attempt", "2",
                "unsafe", "Bearer debug-secret"));

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("request.retry"));
        assertTrue(output.contains("attempt=2"));
        assertTrue(output.contains("model=test-model"));
        assertTrue(output.contains("provider=deepseek"));
        assertFalse(output.contains("debug-secret"));
    }

    @Test
    void warnLevelDoesNotEmitDebugDiagnostics() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PlainDiagnosticSink sink = new PlainDiagnosticSink(
                config(LogLevel.WARN), new PrintStream(bytes, true, StandardCharsets.UTF_8));

        sink.debug("request.start", Map.of("provider", "deepseek"));

        assertEquals("", bytes.toString(StandardCharsets.UTF_8));
    }

    private ChatConfig config(LogLevel level) {
        return new ChatConfig(
                "debug-secret",
                "test-model",
                URI.create("https://api.deepseek.com"),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                level,
                Map.of());
    }
}
