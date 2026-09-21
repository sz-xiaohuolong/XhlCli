package com.xhlcli.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.agent.AgentRunner;
import com.xhlcli.model.RunEventSink;
import com.xhlcli.config.AgentSettings;
import com.xhlcli.config.ChatConfig;
import com.xhlcli.config.ConfigKey;
import com.xhlcli.config.ConfigSource;
import com.xhlcli.config.LogLevel;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.mcp.config.McpConfigLoader;
import com.xhlcli.mcp.manager.McpServerManager;
import com.xhlcli.mcp.model.McpServerConfig;
import com.xhlcli.model.ChatMessage;
import com.xhlcli.model.RunResult;
import com.xhlcli.model.RunStatus;
import com.xhlcli.model.TokenUsage;
import com.xhlcli.render.PlainRunRenderer;
import com.xhlcli.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpCliIntegrationTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ByteArrayOutputStream outContent;
    private ByteArrayOutputStream errContent;
    private PlainRunRenderer renderer;
    private ChatLoop chatLoop;
    private ToolRegistry toolRegistry;
    private McpServerManager mcpManager;

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
        renderer = new PlainRunRenderer(new PrintStream(outContent, true, StandardCharsets.UTF_8),
                new PrintStream(errContent, true, StandardCharsets.UTF_8), "test-api-key");
        toolRegistry = new ToolRegistry(List.of());
        mcpManager = new McpServerManager(toolRegistry, Path.of("."), mapper);
    }

    @AfterEach
    void tearDown() {
        mcpManager.close();
    }

    private ChatConfig createConfig() {
        return new ChatConfig(
                "key", "deepseek-chat", URI.create("https://api.deepseek.com"),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1),
                LogLevel.INFO, new AgentSettings(10, Duration.ofSeconds(600)),
                Map.of(ConfigKey.API_KEY, ConfigSource.DOT_ENV, ConfigKey.MODEL, ConfigSource.DEFAULT)
        );
    }

    @Test
    void testMcpListAndStatusCommands() {
        McpServerConfig server1 = McpServerConfig.stdio("test_fetch", "uvx", List.of("fetch"), Map.of(), true, "test-path");
        mcpManager.registerServers(new McpConfigLoader.LoadResult(
                Map.of("test_fetch", server1),
                Map.of("broken_srv", "Unresolved env: TOKEN")
        ));

        MockScriptedReader reader = new MockScriptedReader(List.of(
                "/mcp list",
                "/mcp status test_fetch",
                "/mcp status broken_srv",
                "/exit"
        ));

        chatLoop = new ChatLoop(reader, new ChatCommandParser(), new StubAgent(), renderer, createConfig());
        chatLoop.setMcpServerManager(mcpManager);

        int exitCode = chatLoop.run();
        assertEquals(0, exitCode);

        String output = outContent.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("MCP Servers 状态列表"));
        assertTrue(output.contains("test_fetch"));
        assertTrue(output.contains("broken_srv"));
        assertTrue(output.contains("Unresolved env: TOKEN"));
        assertTrue(output.contains("MCP Server 详情: test_fetch"));
    }

    @Test
    void testMcpToolsAndResourcesCommands() {
        MockScriptedReader reader = new MockScriptedReader(List.of(
                "/mcp tools",
                "/mcp resources",
                "/exit"
        ));

        chatLoop = new ChatLoop(reader, new ChatCommandParser(), new StubAgent(), renderer, createConfig());
        chatLoop.setMcpServerManager(mcpManager);

        int exitCode = chatLoop.run();
        assertEquals(0, exitCode);

        String output = outContent.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("MCP 可用工具清单"));
        assertTrue(output.contains("MCP 外部资源清单"));
    }
}
