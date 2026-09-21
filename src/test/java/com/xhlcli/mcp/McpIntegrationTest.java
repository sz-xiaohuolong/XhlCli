package com.xhlcli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.agent.ReactAgent;
import com.xhlcli.agent.RunLimits;
import com.xhlcli.hitl.ApprovalPolicy;
import com.xhlcli.llm.*;
import com.xhlcli.mcp.client.McpClient;
import com.xhlcli.mcp.model.McpResourceContent;
import com.xhlcli.mcp.model.McpToolDefinition;
import com.xhlcli.mcp.protocol.JsonRpcRequest;
import com.xhlcli.mcp.protocol.JsonRpcResponse;
import com.xhlcli.mcp.tool.McpToolAdapter;
import com.xhlcli.mcp.transport.McpTransport;
import com.xhlcli.model.*;
import com.xhlcli.policy.AuditLog;
import com.xhlcli.policy.PathGuard;
import com.xhlcli.agent.ScheduledTimeoutScheduler;
import com.xhlcli.config.StreamingSecretRedactor;
import com.xhlcli.tool.DefaultToolExecutor;
import com.xhlcli.tool.ToolRegistry;
import com.xhlcli.tool.ToolResultBudget;
import com.xhlcli.tool.ToolSchemaValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class McpIntegrationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    static class StubMcpTransport implements McpTransport {
        CompletableFuture<JsonRpcResponse> responseFuture = new CompletableFuture<>();

        @Override public void start() throws IOException {}
        @Override public CompletableFuture<JsonRpcResponse> sendRequest(JsonRpcRequest request, CancellationToken cancellationToken) {
            return responseFuture;
        }
        @Override public void sendNotification(com.xhlcli.mcp.protocol.JsonRpcNotification notification) {}
        @Override public void setNotificationListener(java.util.function.Consumer<com.xhlcli.mcp.protocol.JsonRpcNotification> listener) {}
        @Override public boolean isAlive() { return true; }
        @Override public List<String> getRecentLogs() { return List.of(); }
        @Override public void close() {}
    }

    static class StubLlmClient implements LlmClient {
        private int turn = 0;
        private final String toolCallName;

        StubLlmClient(String toolCallName) {
            this.toolCallName = toolCallName;
        }

        @Override public String providerName() { return "stub"; }
        @Override public String modelName() { return "stub-model"; }
        @Override public ModelCapabilities capabilities() { return ModelCapabilities.openAiDefault(); }

        @Override
        public ChatResponse stream(List<ChatMessage> messages, List<ToolDefinition> tools, StreamListener listener, CancellationToken cancellationToken) {
            turn++;
            if (turn == 1) {
                // Return tool call for MCP tool
                ToolCall call = new ToolCall("call_1", toolCallName, "{\"x\": 10, \"y\": 20}");
                return new ChatResponse("", List.of(call), new TokenUsage(10, 5, true));
            } else {
                // Final answer after observation
                return new ChatResponse("Calculation result is 30.", List.of(), new TokenUsage(10, 5, true));
            }
        }

        @Override public void close() {}
    }

    @BeforeEach
    void setUp() {
        ApprovalPolicy.clearTrustedMcp();
    }

    @AfterEach
    void tearDown() {
        ApprovalPolicy.clearTrustedMcp();
    }

    @Test
    void testEndToEndMcpToolCallThroughReactAgent(@TempDir Path tempDir) throws Exception {
        StubMcpTransport transport = new StubMcpTransport();
        McpClient mcpClient = new McpClient("math_server", transport, mapper);

        // Initialize MCP client
        transport.responseFuture = CompletableFuture.completedFuture(
                JsonRpcResponse.success(1L, mapper.readTree("{\"protocolVersion\":\"2024-11-05\"}")));
        mcpClient.initialize(Duration.ofSeconds(2));

        // Create tool definition
        JsonNode inputSchema = mapper.readTree("""
                {
                  "type": "object",
                  "properties": {
                    "x": { "type": "number" },
                    "y": { "type": "number" }
                  },
                  "required": ["x", "y"]
                }
                """);
        McpToolDefinition def = new McpToolDefinition("add", "Add two numbers", inputSchema);
        McpToolAdapter mcpTool = new McpToolAdapter("math_server", def, mcpClient, mapper, Duration.ofSeconds(5), true);

        // Register in ToolRegistry
        ToolRegistry registry = new ToolRegistry(List.of(mcpTool));
        assertEquals(1, registry.definitions().size());
        assertEquals("mcp__math_server__add", registry.definitions().get(0).name());

        // Prepare DefaultToolExecutor with PathGuard & AuditLog
        PathGuard pathGuard = new PathGuard(tempDir);
        AuditLog auditLog = new AuditLog(tempDir.resolve("audit"));
        DefaultToolExecutor executor = new DefaultToolExecutor(
                registry, new ToolSchemaValidator(mapper), new ToolResultBudget(1000, mapper),
                mapper, System::nanoTime, pathGuard, null, auditLog);

        // Prepare ReactAgent
        StubLlmClient llmClient = new StubLlmClient("mcp__math_server__add");
        try (ScheduledTimeoutScheduler scheduler = new ScheduledTimeoutScheduler()) {
            ReactAgent agent = new ReactAgent(
                    ChatMessage.system("You are a test assistant."),
                    llmClient, executor, registry.definitions(),
                    new RunLimits(5, Duration.ofSeconds(10)), scheduler,
                    mapper, Clock.systemUTC(), () -> UUID.randomUUID().toString(),
                    s -> s, () -> new StreamingSecretRedactor(null));

            // Prepare tool call response for MCP server
            transport.responseFuture = CompletableFuture.completedFuture(
                    JsonRpcResponse.success(2L, mapper.readTree("{\"content\":[{\"type\":\"text\",\"text\":\"30\"}],\"isError\":false}")));

            // Run turn
            RunResult result = agent.run("Please add 10 and 20", (event) -> {}, new CancellationToken());

            assertNotNull(result);
            assertEquals(RunStatus.COMPLETED, result.status());
            assertEquals("Calculation result is 30.", result.finalAnswer());
        }
    }
}
