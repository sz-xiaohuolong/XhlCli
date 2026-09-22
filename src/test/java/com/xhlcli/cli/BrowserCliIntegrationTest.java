package com.xhlcli.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhlcli.agent.AgentRunner;
import com.xhlcli.browser.BrowserGuard;
import com.xhlcli.browser.BrowserMode;
import com.xhlcli.browser.BrowserSession;
import com.xhlcli.browser.DefaultBrowserConnector;
import com.xhlcli.browser.SensitivePagePolicy;
import com.xhlcli.browser.tool.BrowserConnectTool;
import com.xhlcli.browser.tool.BrowserDisconnectTool;
import com.xhlcli.browser.tool.BrowserStatusTool;
import com.xhlcli.config.AgentSettings;
import com.xhlcli.config.ChatConfig;
import com.xhlcli.config.ConfigKey;
import com.xhlcli.config.ConfigSource;
import com.xhlcli.config.LogLevel;
import com.xhlcli.hitl.HitlHandler;
import com.xhlcli.hitl.TerminalHitlHandler;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.ChatMessage;
import com.xhlcli.model.RunEventSink;
import com.xhlcli.model.RunResult;
import com.xhlcli.model.RunStatus;
import com.xhlcli.model.TokenUsage;
import com.xhlcli.model.ToolMetadata;
import com.xhlcli.model.ToolOutput;
import com.xhlcli.model.ToolResult;
import com.xhlcli.model.ToolResultStatus;
import com.xhlcli.policy.AuditLog;
import com.xhlcli.render.PlainRunRenderer;
import com.xhlcli.tool.DefaultToolExecutor;
import com.xhlcli.tool.Tool;
import com.xhlcli.tool.ToolRegistry;
import com.xhlcli.tool.ToolResultBudget;
import com.xhlcli.tool.ToolSchemaValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BrowserCliIntegrationTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ByteArrayOutputStream outContent;
    private ByteArrayOutputStream errContent;
    private PlainRunRenderer renderer;

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
                "test-api-key"
        );
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
    void testCommandParserRecognizesBrowser() {
        ChatCommandParser parser = new ChatCommandParser();
        assertEquals(ChatCommand.BROWSER, parser.parse("/browser"));
        assertEquals(ChatCommand.BROWSER, parser.parse("/browser status"));
        assertEquals(ChatCommand.BROWSER, parser.parse("/browser connect 9222"));
        assertEquals(ChatCommand.BROWSER, parser.parse("/browser disconnect"));
        assertEquals(ChatCommand.BROWSER, parser.parse("/browser tabs"));
    }

    @Test
    void testBrowserToolsExecution() {
        BrowserSession session = new BrowserSession();
        SensitivePagePolicy policy = new SensitivePagePolicy(null);
        DefaultBrowserConnector connector = new DefaultBrowserConnector(
                session,
                new com.xhlcli.browser.BrowserConnectivityCheck(),
                policy
        );

        BrowserStatusTool statusTool = new BrowserStatusTool(connector);
        ToolOutput statusOut = statusTool.execute(mapper.createObjectNode(), new CancellationToken());
        assertTrue(statusOut.summary().contains("ISOLATED"));
        assertEquals("ISOLATED", statusOut.data().get("mode").asText());

        BrowserDisconnectTool disconnectTool = new BrowserDisconnectTool(connector);
        ToolOutput disconnectOut = disconnectTool.execute(mapper.createObjectNode(), new CancellationToken());
        assertTrue(disconnectOut.summary().contains("ISOLATED"));
        assertEquals("ISOLATED", disconnectOut.data().get("mode").asText());

        BrowserConnectTool connectTool = new BrowserConnectTool(connector);
        ObjectNode args = mapper.createObjectNode();
        args.put("port", 65530);
        ToolOutput connectOut = connectTool.execute(args, new CancellationToken());
        assertNotNull(connectOut.summary());
    }

    @Test
    void testChatLoopBrowserCommands() {
        List<String> inputs = List.of(
                "/browser",
                "/browser status",
                "/browser connect 65530",
                "/browser tabs",
                "/browser disconnect"
        );
        MockScriptedReader reader = new MockScriptedReader(inputs);
        ChatCommandParser parser = new ChatCommandParser();
        StubAgent agent = new StubAgent();
        ChatLoop loop = new ChatLoop(reader, parser, agent, renderer, createConfig());

        BrowserSession session = new BrowserSession();
        DefaultBrowserConnector connector = new DefaultBrowserConnector(
                session,
                new com.xhlcli.browser.BrowserConnectivityCheck(),
                new SensitivePagePolicy(null)
        );
        loop.setBrowserConnector(connector);

        int exitCode = loop.run();
        assertEquals(0, exitCode);

        String output = outContent.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("浏览器会话状态"));
        assertTrue(output.contains("ISOLATED"));
        assertTrue(output.contains("无法连接到宿主 Chromium") || output.contains("端口"));
        assertTrue(output.contains("当前未连接外部浏览器或无打开的标签页"));
        assertTrue(output.contains("已断开与宿主 Chromium 的共享会话"));
    }

    @Test
    void testDefaultToolExecutorBlocksHostTabCloseInSharedMode() {
        BrowserSession session = new BrowserSession();
        session.switchToShared("http://127.0.0.1:9222"); // SHARED mode
        session.recordOpenedTab("page-agent-1"); // Agent tab

        SensitivePagePolicy policy = new SensitivePagePolicy(null);
        BrowserGuard guard = new BrowserGuard(session, policy);

        Tool mockClosePageTool = new Tool() {
            @Override
            public com.xhlcli.model.ToolDefinition definition() {
                ObjectNode params = JsonNodeFactory.instance.objectNode();
                params.put("type", "object");
                return new com.xhlcli.model.ToolDefinition(
                        "mcp__chrome_devtools__close_page",
                        "close page",
                        params,
                        new ToolMetadata(ToolMetadata.RiskLevel.LOW, false, false, false, "browser")
                );
            }

            @Override
            public ToolOutput execute(com.fasterxml.jackson.databind.JsonNode arguments, CancellationToken token) {
                return new ToolOutput("closed", JsonNodeFactory.instance.objectNode(), "");
            }
        };

        ToolRegistry registry = new ToolRegistry(List.of(mockClosePageTool));
        DefaultToolExecutor executor = new DefaultToolExecutor(
                registry,
                new ToolSchemaValidator(mapper),
                new ToolResultBudget(ToolResultBudget.DEFAULT_MAX_CHARS, mapper),
                mapper,
                System::nanoTime,
                null,
                null,
                null,
                guard
        );

        // 1. Trying to close non-agent tab: should be blocked by hard policy
        ToolResult blockResult = executor.execute(
                new com.xhlcli.model.ToolCall("call-1", "mcp__chrome_devtools__close_page", "{\"pageId\":\"page-user-host-tab\"}"),
                new CancellationToken()
        );
        assertEquals(ToolResultStatus.EXECUTION_ERROR, blockResult.status());
        assertTrue(blockResult.summary().contains("shared 浏览器模式下拒绝关闭非 XhlCLI 创建的标签页"));

        // 2. Closing agent-owned tab: allowed
        ToolResult allowResult = executor.execute(
                new com.xhlcli.model.ToolCall("call-2", "mcp__chrome_devtools__close_page", "{\"pageId\":\"page-agent-1\"}"),
                new CancellationToken()
        );
        assertEquals(ToolResultStatus.SUCCESS, allowResult.status());
    }

    @Test
    void testSensitivePageRequiresSingleStepApprovalEvenWhenAllApproved(@TempDir Path tempDir) {
        BrowserSession session = new BrowserSession();
        session.switchToShared("http://127.0.0.1:9222");
        session.rememberNavigation("https://stripe.com/checkout");

        SensitivePagePolicy policy = new SensitivePagePolicy(null);
        BrowserGuard guard = new BrowserGuard(session, policy);

        Tool mockClickTool = new Tool() {
            @Override
            public com.xhlcli.model.ToolDefinition definition() {
                ObjectNode params = JsonNodeFactory.instance.objectNode();
                params.put("type", "object");
                return new com.xhlcli.model.ToolDefinition(
                        "mcp__chrome_devtools__click",
                        "click element",
                        params,
                        new ToolMetadata(ToolMetadata.RiskLevel.LOW, false, false, false, "browser")
                );
            }

            @Override
            public ToolOutput execute(com.fasterxml.jackson.databind.JsonNode arguments, CancellationToken token) {
                return new ToolOutput("clicked", JsonNodeFactory.instance.objectNode(), "");
            }
        };

        AtomicInteger hitlPromptCount = new AtomicInteger(0);
        HitlHandler hitlHandler = new HitlHandler() {
            private boolean enabled = true;
            private boolean approveAll = true;

            @Override
            public boolean isEnabled() { return enabled; }

            @Override
            public void setEnabled(boolean enabled) { this.enabled = enabled; }

            @Override
            public com.xhlcli.hitl.ApprovalResult requestApproval(com.xhlcli.hitl.ApprovalRequest request) {
                hitlPromptCount.incrementAndGet();
                return com.xhlcli.hitl.ApprovalResult.approve();
            }

            @Override
            public boolean isApprovedAllByTool(String toolName) {
                return approveAll;
            }

            @Override
            public void clearApprovedAll() {
                this.approveAll = false;
            }
        };

        // User previously chose "Approve All" for this tool
        assertTrue(hitlHandler.isApprovedAllByTool("mcp__chrome_devtools__click"));

        ToolRegistry registry = new ToolRegistry(List.of(mockClickTool));
        DefaultToolExecutor executor = new DefaultToolExecutor(
                registry,
                new ToolSchemaValidator(mapper),
                new ToolResultBudget(ToolResultBudget.DEFAULT_MAX_CHARS, mapper),
                mapper,
                System::nanoTime,
                null,
                hitlHandler,
                null,
                guard
        );

        // Even though approveAll was set, sensitive write action on stripe.com forces approval!
        ToolResult result = executor.execute(
                new com.xhlcli.model.ToolCall("call-click-1", "mcp__chrome_devtools__click", "{\"selector\":\"#pay-btn\"}"),
                new CancellationToken()
        );

        assertEquals(ToolResultStatus.SUCCESS, result.status());
        assertEquals(1, hitlPromptCount.get(), "Must have prompted HITL despite approveAll being true!");
    }
}
