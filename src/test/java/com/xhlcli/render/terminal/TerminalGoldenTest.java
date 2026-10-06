package com.xhlcli.render.terminal;

import com.xhlcli.model.RunEvent;
import com.xhlcli.model.RunResult;
import com.xhlcli.model.RunStatus;
import com.xhlcli.model.TokenUsage;
import com.xhlcli.model.ToolResultStatus;
import com.xhlcli.render.PlainRunRenderer;
import com.xhlcli.render.TerminalExtSummary;
import com.xhlcli.render.TerminalStatus;
import com.xhlcli.render.format.MarkdownRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Phase 15 Terminal Golden Test Suite")
class TerminalGoldenTest {

    private static final Pattern ANSI_PATTERN = Pattern.compile("\\u001B\\[[0-9;]*[a-zA-Z]");

    @Test
    @DisplayName("Golden 1: 80/120/160 columns status bar priority truncation and zero-newline guarantee")
    void goldenStatusBarColumnsTruncation() {
        TerminalStatus status = new TerminalStatus(
                "ReAct",
                "ToolExecution",
                "deepseek-chat",
                4200,
                128000,
                3,
                5,
                "/Users/developer/projects/super-long-repository-directory-path"
        );

        // 80 columns: P0 guaranteed, P3 workspace dropped or truncated
        String line80 = TerminalStatusBar.formatPlain(status, 80);
        assertFalse(line80.contains("\n"), "Status line must have zero newlines at 80 cols");
        assertFalse(line80.contains("\r"), "Status line must have zero carriage returns at 80 cols");
        int width80 = TerminalWidthCalculator.displayWidth(line80);
        assertTrue(width80 <= 80, "Visual width must be <= 80 cols, actual: " + width80);
        assertTrue(line80.contains("ReAct"), "P0 Mode must be present");
        assertTrue(line80.contains("ToolExecution"), "P0 Phase must be present");
        assertTrue(line80.contains("deepseek-chat"), "P0 Model must be present");

        // 120 columns: P0 + P1 Tokens present
        String line120 = TerminalStatusBar.formatPlain(status, 120);
        assertFalse(line120.contains("\n"), "Status line must have zero newlines at 120 cols");
        int width120 = TerminalWidthCalculator.displayWidth(line120);
        assertTrue(width120 <= 120, "Visual width must be <= 120 cols, actual: " + width120);
        assertTrue(line120.contains("ReAct"));
        assertTrue(line120.contains("4.2k/128.0k"), "P1 Tokens should be present at 120 cols");

        // 160 columns: P0 + P1 + P2 + P3 present
        String line160 = TerminalStatusBar.formatPlain(status, 160);
        assertFalse(line160.contains("\n"), "Status line must have zero newlines at 160 cols");
        int width160 = TerminalWidthCalculator.displayWidth(line160);
        assertTrue(width160 <= 160, "Visual width must be <= 160 cols, actual: " + width160);
        assertTrue(line160.contains("MCP:3"));
        assertTrue(line160.contains("Skills:5"));
    }

    @Test
    @DisplayName("Golden 2: Markdown table formatting with CJK/Emoji and exact column boundary alignment")
    void goldenMarkdownTableAlignment() {
        String inputTable = """
                | 序号 | 模块名称 | 状态 | 说明 |
                | :--- | :--- | :--- | :--- |
                | 1 | 终端渲染器 | ✅ 正常 | 行内与纯文本双模实现 |
                | 2 | Git Diff | ✅ 正常 | 彩色高亮 Unified Diff 补丁渲染 |
                """;

        String rendered = MarkdownRenderer.render(inputTable, 80, false);
        assertNotNull(rendered);
        List<String> lines = rendered.lines().filter(l -> !l.isBlank()).toList();

        // Must contain bordered table characters
        assertTrue(lines.stream().anyMatch(l -> l.contains("┌") && l.contains("┬") && l.contains("┐")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("├") && l.contains("┼") && l.contains("┤")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("└") && l.contains("┴") && l.contains("┘")));

        // All table box rows must have the exact same visual display width
        int expectedWidth = -1;
        for (String line : lines) {
            if (line.contains("│") || line.contains("┌") || line.contains("├") || line.contains("└")) {
                int w = TerminalWidthCalculator.displayWidth(line);
                if (expectedWidth == -1) {
                    expectedWidth = w;
                } else {
                    assertEquals(expectedWidth, w, "All bordered rows must align to visual width: " + line);
                }
            }
        }
    }

    @Test
    @DisplayName("Golden 3: PlainRunRenderer zero-ANSI guarantee across all events and welcome screen")
    void goldenPlainRunRendererZeroAnsiGuarantee() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String apiKey = "sk-test-secret-key-1234567890123456";

        try (PlainRunRenderer renderer = new PlainRunRenderer(
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                apiKey
        )) {
            // Welcome screen with ANSI injection attempts
            renderer.printWelcome("\u001B[31mdeepseek-chat\u001B[0m", "0.13.0", "/Users/\u001B[32mworkspace\u001B[0m",
                    new TerminalExtSummary(2, 3, true));

            // Help & Config
            renderer.printHelp();

            // Run lifecycle events
            RunEvent.Metadata meta = new RunEvent.Metadata("run-1", 1, Instant.now(), 1);
            renderer.accept(new RunEvent.RunStarted(meta, "test run"));
            renderer.accept(new RunEvent.ModelRequestStarted(meta));
            renderer.accept(new RunEvent.TextDelta(meta, "Here is \u001B[33mcolor\u001B[0m and `code` test: " + apiKey));
            renderer.accept(new RunEvent.ToolStarted(meta, "execute_command", "{\"cmd\":\"ls\"}"));
            renderer.accept(new RunEvent.ToolCompleted(meta, "execute_command", ToolResultStatus.SUCCESS, 25, "file1.txt\nfile2.txt"));
            renderer.accept(new RunEvent.RunCompleted(meta, "Done", new TokenUsage(100, 50, true)));

            renderer.printMessage("Normal \u001B[1mbold\u001B[0m text");
            renderer.printErrorMessage("Error \u001B[31mfailure\u001B[0m text");
        }

        String outText = out.toString(StandardCharsets.UTF_8);
        String errText = err.toString(StandardCharsets.UTF_8);

        // Strict assertion: zero ANSI escape codes in standard out
        assertFalse(ANSI_PATTERN.matcher(outText).find(), "PlainRunRenderer stdout must contain zero ANSI escapes: " + outText);
        assertFalse(outText.contains("\u001B"), "PlainRunRenderer stdout must not contain \\u001B");

        // Strict assertion: zero ANSI escape codes in standard err
        assertFalse(ANSI_PATTERN.matcher(errText).find(), "PlainRunRenderer stderr must contain zero ANSI escapes: " + errText);
        assertFalse(errText.contains("\u001B"), "PlainRunRenderer stderr must not contain \\u001B");

        // Secret redactor assertion: API key must be redacted
        assertFalse(outText.contains(apiKey), "Sensitive API key must be redacted in output");
        assertTrue(outText.contains("***"), "Redacted placeholder *** must be present");
    }
}
