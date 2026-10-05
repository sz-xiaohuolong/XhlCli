package com.xhlcli.cli;

import com.xhlcli.agent.AgentRunner;
import com.xhlcli.config.AgentSettings;
import com.xhlcli.config.ChatConfig;
import com.xhlcli.config.ConfigKey;
import com.xhlcli.config.ConfigSource;
import com.xhlcli.config.LogLevel;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.ChatMessage;
import com.xhlcli.model.RunEventSink;
import com.xhlcli.model.RunResult;
import com.xhlcli.model.RunStatus;
import com.xhlcli.model.TokenUsage;
import com.xhlcli.prompt.LayeredPromptAssembler;
import com.xhlcli.prompt.PromptLayer;
import com.xhlcli.prompt.PromptSource;
import com.xhlcli.render.PlainRunRenderer;
import com.xhlcli.skill.SkillRegistry;
import com.xhlcli.skill.builtin.BuiltinSkills;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SkillPromptCliTest {

    private ByteArrayOutputStream outContent;
    private ByteArrayOutputStream errContent;
    private PlainRunRenderer renderer;
    private SkillRegistry skillRegistry;
    private LayeredPromptAssembler promptAssembler;

    static class MockScriptedReader implements InputReader {
        private final List<String> inputs;
        private int index = 0;

        MockScriptedReader(List<String> inputs) {
            this.inputs = inputs;
        }

        @Override
        public String readLine(String prompt) throws InputInterruptedException, InputEndOfFileException {
            if (index < inputs.size()) {
                return inputs.get(index++);
            }
            throw new InputEndOfFileException();
        }
    }

    static class StubAgent implements AgentRunner {
        @Override
        public RunResult run(String input, RunEventSink events, CancellationToken token) {
            return new RunResult("r1", RunStatus.COMPLETED, "ok", "", 0, TokenUsage.unknown());
        }

        @Override
        public void clearHistory() {}

        @Override
        public List<ChatMessage> history() {
            return List.of();
        }
    }

    @BeforeEach
    void setUp() {
        outContent = new ByteArrayOutputStream();
        errContent = new ByteArrayOutputStream();
        renderer = new PlainRunRenderer(
                new PrintStream(outContent, true, StandardCharsets.UTF_8),
                new PrintStream(errContent, true, StandardCharsets.UTF_8),
                "test-secret-key-12345"
        );
        skillRegistry = new SkillRegistry(null, null, BuiltinSkills.all());
        skillRegistry.scanAndReload();

        promptAssembler = new LayeredPromptAssembler();
        promptAssembler.registerBlock(
                PromptLayer.PROJECT_RULES_AND_MEMORY,
                PromptSource.PROJECT,
                "## Project Secrets",
                "apiKey: test-secret-key-12345\nUse Java 21."
        );
    }

    private ChatConfig createConfig() {
        return new ChatConfig(
                "test-secret-key-12345", "deepseek-chat", URI.create("https://api.deepseek.com"),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1),
                LogLevel.INFO, new AgentSettings(10, Duration.ofSeconds(600)),
                Map.of(ConfigKey.API_KEY, ConfigSource.DOT_ENV, ConfigKey.MODEL, ConfigSource.DEFAULT)
        );
    }

    @Test
    void testCommandParserRecognizesSkillAndPrompt() {
        ChatCommandParser parser = new ChatCommandParser();
        assertEquals(ChatCommand.SKILL, parser.parse("/skill"));
        assertEquals(ChatCommand.SKILL, parser.parse("/skill list"));
        assertEquals(ChatCommand.SKILL, parser.parse("/skill show git-feature-workflow"));
        assertEquals(ChatCommand.SKILL, parser.parse("/skill enable abc"));
        assertEquals(ChatCommand.SKILL, parser.parse("/skill disable abc"));
        assertEquals(ChatCommand.SKILL, parser.parse("/skill reload"));

        assertEquals(ChatCommand.PROMPT, parser.parse("/prompt"));
        assertEquals(ChatCommand.PROMPT, parser.parse("/prompt show"));
        assertEquals(ChatCommand.PROMPT, parser.parse("/prompt export"));
    }

    @Test
    void testSkillListAndShowCommands() {
        MockScriptedReader reader = new MockScriptedReader(List.of(
                "/skill list",
                "/skill show git-feature-workflow",
                "/exit"
        ));

        ChatLoop loop = new ChatLoop(reader, new ChatCommandParser(), new StubAgent(), renderer, createConfig());
        loop.setSkillRegistry(skillRegistry);
        loop.run();

        String out = outContent.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("已安装 Skill 列表"));
        assertTrue(out.contains("git-feature-workflow"));
        assertTrue(out.contains("web-research"));
        assertTrue(out.contains("Skill 详情: git-feature-workflow"));
        assertTrue(out.contains("Conventional Commits"));
    }

    @Test
    void testSkillEnableDisableAndReloadCommands() {
        MockScriptedReader reader = new MockScriptedReader(List.of(
                "/skill disable git-feature-workflow",
                "/skill enable git-feature-workflow",
                "/skill reload",
                "/exit"
        ));

        ChatLoop loop = new ChatLoop(reader, new ChatCommandParser(), new StubAgent(), renderer, createConfig());
        loop.setSkillRegistry(skillRegistry);
        loop.run();

        String out = outContent.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("Skill 'git-feature-workflow' 已成功禁用"));
        assertTrue(out.contains("Skill 'git-feature-workflow' 已成功启用"));
        assertTrue(out.contains("已完成 Skill 目录热重载"));
    }

    @Test
    void testPromptShowAndExportWithSecretRedaction(@TempDir Path tempDir) throws Exception {
        Path exportFile = tempDir.resolve("exported-prompt.md");
        MockScriptedReader reader = new MockScriptedReader(List.of(
                "/prompt show",
                "/prompt export " + exportFile.toAbsolutePath(),
                "/exit"
        ));

        ChatLoop loop = new ChatLoop(reader, new ChatCommandParser(), new StubAgent(), renderer, createConfig());
        loop.setPromptAssembler(promptAssembler);
        loop.setProjectDirectory(tempDir);
        loop.run();

        String out = outContent.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("Layered System Prompt Audit Dump"));
        assertTrue(out.contains("[Layer 1: Base Identity"));
        assertTrue(out.contains("[Layer 2: Safety Policy"));

        // Sensitive key must NOT leak in output!
        assertFalse(out.contains("test-secret-key-12345"));
        assertTrue(out.contains("***") || out.contains("[REDACTED]"));

        // Check exported file
        assertTrue(Files.exists(exportFile));
        String fileContent = Files.readString(exportFile, StandardCharsets.UTF_8);
        assertTrue(fileContent.contains("Layered System Prompt Audit Dump"));
        assertFalse(fileContent.contains("test-secret-key-12345"));
        assertTrue(fileContent.contains("***") || fileContent.contains("[REDACTED]"));
    }
}
