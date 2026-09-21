package com.xhlcli.mcp.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.hitl.ApprovalPolicy;
import com.xhlcli.hitl.RiskLevel;
import com.xhlcli.mcp.client.McpClient;
import com.xhlcli.mcp.model.McpToolDefinition;
import com.xhlcli.mcp.protocol.JsonRpcRequest;
import com.xhlcli.mcp.protocol.JsonRpcResponse;
import com.xhlcli.mcp.transport.McpTransport;
import com.xhlcli.model.ToolOutput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class McpToolAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private McpClient client;
    private FakeTransport transport;

    static class FakeTransport implements McpTransport {
        CompletableFuture<JsonRpcResponse> nextResponse = new CompletableFuture<>();

        @Override public void start() throws IOException {}
        @Override public CompletableFuture<JsonRpcResponse> sendRequest(JsonRpcRequest r, com.xhlcli.llm.CancellationToken c) {
            return nextResponse;
        }
        @Override public void sendNotification(com.xhlcli.mcp.protocol.JsonRpcNotification n) {}
        @Override public void setNotificationListener(java.util.function.Consumer<com.xhlcli.mcp.protocol.JsonRpcNotification> l) {}
        @Override public boolean isAlive() { return true; }
        @Override public java.util.List<String> getRecentLogs() { return java.util.List.of(); }
        @Override public void close() {}
    }

    @BeforeEach
    void setUp() {
        transport = new FakeTransport();
        client = new McpClient("fetch_server", transport, mapper);
        ApprovalPolicy.clearTrustedMcp();
    }

    @AfterEach
    void tearDown() {
        ApprovalPolicy.clearTrustedMcp();
    }

    @Test
    void testNormalizeToolNameAndSanitizeSchema() {
        JsonNode rawSchema = mapper.createObjectNode().put("type", "invalid");
        McpToolDefinition mcpTool = new McpToolDefinition("query-docs", "Search documentation", rawSchema);

        McpToolAdapter adapter = new McpToolAdapter("my-service", mcpTool, client, mapper, Duration.ofSeconds(5), false);

        assertEquals("mcp__my_service__query_docs", adapter.definition().name());
        assertEquals("Search documentation", adapter.definition().description());
        assertEquals("object", adapter.definition().parameters().path("type").asText());
        assertNotNull(adapter.definition().parameters().path("properties"));
    }

    @Test
    void testExecutionSuccessWithTextAndImage() throws Exception {
        McpToolDefinition mcpTool = new McpToolDefinition("render", "Render UI", mapper.createObjectNode().put("type", "object"));
        McpToolAdapter adapter = new McpToolAdapter("ui_server", mcpTool, client, mapper, Duration.ofSeconds(5), false);

        // Mock initialize handshake
        transport.nextResponse = CompletableFuture.completedFuture(
                JsonRpcResponse.success(1L, mapper.readTree("{\"protocolVersion\":\"2024-11-05\"}")));
        client.initialize(Duration.ofSeconds(2));

        // Mock tool call response
        JsonNode callResult = mapper.readTree("""
                {
                  "content": [
                    { "type": "text", "text": "UI rendered successfully" },
                    { "type": "image", "data": "YWJj", "mimeType": "image/png" }
                  ],
                  "isError": false
                }
                """);
        transport.nextResponse = CompletableFuture.completedFuture(JsonRpcResponse.success(2L, callResult));

        ToolOutput output = adapter.execute(mapper.createObjectNode(), null);

        assertNotNull(output);
        assertTrue(output.summary().contains("UI rendered successfully"));
        assertTrue(output.summary().contains("[Image: mimeType=image/png, size=4 bytes]"));
    }

    @Test
    void testApprovalPolicyWithMcpTools() {
        String untrustedTool = "mcp__github__create_issue";
        assertTrue(ApprovalPolicy.requiresApproval(untrustedTool));
        assertEquals(RiskLevel.MEDIUM_RISK, ApprovalPolicy.getRiskLevel(untrustedTool));

        // Register server as trusted read-only
        ApprovalPolicy.registerTrustedMcpServer("github");
        assertFalse(ApprovalPolicy.requiresApproval(untrustedTool));
        assertEquals(RiskLevel.READ_ONLY, ApprovalPolicy.getRiskLevel(untrustedTool));
    }
}
