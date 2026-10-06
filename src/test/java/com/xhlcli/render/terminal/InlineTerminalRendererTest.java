package com.xhlcli.render.terminal;

import com.xhlcli.model.RunEvent;
import com.xhlcli.model.TokenUsage;
import com.xhlcli.model.ToolResultStatus;
import com.xhlcli.render.TerminalExtSummary;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("InlineTerminalRenderer Tests")
class InlineTerminalRendererTest {

    private static RunEvent.Metadata metadata(long seq) {
        return new RunEvent.Metadata("run-1", seq, Instant.now(), 0);
    }

    private static Terminal createTestTerminal(ByteArrayOutputStream out) throws IOException {
        return new org.jline.terminal.impl.DumbTerminal("xterm", "xterm-256color", new ByteArrayInputStream(new byte[0]), out, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Should render welcome screen with ANSI styles and extension summary")
    void shouldRenderWelcomeScreen() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Terminal terminal = createTestTerminal(out);

        InlineTerminalRenderer renderer = new InlineTerminalRenderer(terminal, null, "test-api-key");
        renderer.printWelcome("deepseek-chat", "0.13.0", "/test/workspace", new TerminalExtSummary(2, 3, true));

        String rendered = out.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("XhlCLI v0.13.0"));
        assertTrue(rendered.contains("AI Model: deepseek-chat"));
        assertTrue(rendered.contains("Workspace: /test/workspace"));
        assertTrue(rendered.contains("MCP: 2 | Skills: 3 | Browser: On"));
        assertTrue(rendered.contains("\u001B["));
    }

    @Test
    @DisplayName("Should render streaming timeline, redact secrets, and handle tool events")
    void shouldRenderTimelineAndRedactSecrets() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Terminal terminal = createTestTerminal(out);

        InlineTerminalRenderer renderer = new InlineTerminalRenderer(terminal, null, "super-secret-key");

        renderer.accept(new RunEvent.RunStarted(metadata(1), "init"));
        renderer.accept(new RunEvent.ModelRequestStarted(metadata(2)));
        renderer.accept(new RunEvent.TextDelta(metadata(3), "Hello secret: super-secret-key and world!"));
        renderer.accept(new RunEvent.ToolStarted(metadata(4), "readFile", "path=src/test.txt, key=super-secret-key"));
        renderer.accept(new RunEvent.ToolCompleted(metadata(5), "readFile", ToolResultStatus.SUCCESS, 25, "OK"));
        renderer.accept(new RunEvent.RunCompleted(metadata(6), "done", new TokenUsage(100, 50, true)));

        String rendered = out.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("Thinking..."));
        assertTrue(rendered.contains("Assistant:"));
        assertTrue(rendered.contains("Hello secret:"));
        assertFalse(rendered.contains("super-secret-key"), "API key must be redacted");
        assertTrue(rendered.contains("Tool"));
        assertTrue(rendered.contains("readFile"));
        assertTrue(rendered.contains("任务完成"));
        assertTrue(rendered.contains("[tokens: in=100, out=50]"));
    }

    @Test
    @DisplayName("Should render error messages with red ANSI style")
    void shouldRenderErrorMessage() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Terminal terminal = createTestTerminal(out);

        InlineTerminalRenderer renderer = new InlineTerminalRenderer(terminal, null, "test-key");
        renderer.printErrorMessage("Connection failed");

        String rendered = out.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("Connection failed"));
        assertTrue(rendered.contains("\u001B[31m"));
    }
}
