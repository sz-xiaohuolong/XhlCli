package com.xhlcli.mcp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.mcp.model.McpCallResult;
import com.xhlcli.mcp.model.McpResource;
import com.xhlcli.mcp.model.McpResourceContent;
import com.xhlcli.mcp.model.McpToolDefinition;
import com.xhlcli.mcp.protocol.JsonRpcNotification;
import com.xhlcli.mcp.protocol.JsonRpcRequest;
import com.xhlcli.mcp.protocol.JsonRpcResponse;
import com.xhlcli.mcp.protocol.McpConstants;
import com.xhlcli.mcp.transport.McpTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class McpClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private FakeTransport transport;
    private McpClient client;

    static class FakeTransport implements McpTransport {
        private Consumer<JsonRpcNotification> listener = n -> {};
        private final List<JsonRpcRequest> sentRequests = new ArrayList<>();
        private final List<JsonRpcNotification> sentNotifications = new ArrayList<>();
        private CompletableFuture<JsonRpcResponse> nextResponse = new CompletableFuture<>();

        void prepareResponse(JsonRpcResponse response) {
            nextResponse = CompletableFuture.completedFuture(response);
        }

        void triggerNotification(JsonRpcNotification notification) {
            listener.accept(notification);
        }

        @Override
        public void start() throws IOException {}

        @Override
        public CompletableFuture<JsonRpcResponse> sendRequest(JsonRpcRequest request, CancellationToken cancellationToken) {
            sentRequests.add(request);
            CompletableFuture<JsonRpcResponse> f = nextResponse;
            nextResponse = new CompletableFuture<>();
            return f;
        }

        @Override
        public void sendNotification(JsonRpcNotification notification) throws IOException {
            sentNotifications.add(notification);
        }

        @Override
        public void setNotificationListener(Consumer<JsonRpcNotification> listener) {
            this.listener = listener;
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public List<String> getRecentLogs() {
            return List.of();
        }

        @Override
        public void close() throws IOException {}
    }

    @BeforeEach
    void setUp() {
        transport = new FakeTransport();
        client = new McpClient("test-server", transport, mapper);
    }

    @Test
    void testInitializeHandshake() throws Exception {
        JsonNode initResult = mapper.readTree("""
                {
                  "protocolVersion": "2024-11-05",
                  "capabilities": { "tools": {}, "resources": {} },
                  "serverInfo": { "name": "mock", "version": "1.0" }
                }
                """);
        transport.prepareResponse(JsonRpcResponse.success(1L, initResult));

        client.initialize(Duration.ofSeconds(2));

        assertTrue(client.isInitialized());
        assertEquals(1, transport.sentRequests.size());
        assertEquals(McpConstants.METHOD_INITIALIZE, transport.sentRequests.get(0).method());

        assertEquals(1, transport.sentNotifications.size());
        assertEquals(McpConstants.METHOD_NOTIFICATIONS_INITIALIZED, transport.sentNotifications.get(0).method());
    }

    @Test
    void testListTools() throws Exception {
        testInitializeHandshake();

        JsonNode toolsResult = mapper.readTree("""
                {
                  "tools": [
                    {
                      "name": "fetch",
                      "description": "Fetch a URL",
                      "inputSchema": {
                        "type": "object",
                        "properties": { "url": { "type": "string" } },
                        "required": ["url"]
                      }
                    }
                  ]
                }
                """);
        transport.prepareResponse(JsonRpcResponse.success(2L, toolsResult));

        List<McpToolDefinition> tools = client.listTools(Duration.ofSeconds(2));
        assertEquals(1, tools.size());
        assertEquals("fetch", tools.get(0).name());
        assertEquals("Fetch a URL", tools.get(0).description());
        assertEquals("object", tools.get(0).inputSchema().path("type").asText());
    }

    @Test
    void testCallTool() throws Exception {
        testInitializeHandshake();

        JsonNode callResult = mapper.readTree("""
                {
                  "content": [
                    { "type": "text", "text": "fetched contents" },
                    { "type": "image", "data": "base64img...", "mimeType": "image/png" }
                  ],
                  "isError": false
                }
                """);
        transport.prepareResponse(JsonRpcResponse.success(2L, callResult));

        JsonNode args = mapper.createObjectNode().put("url", "https://example.com");
        McpCallResult result = client.callTool("fetch", args, null, Duration.ofSeconds(2));

        assertFalse(result.isError());
        assertEquals(2, result.contents().size());
        assertEquals("text", result.contents().get(0).type());
        assertEquals("fetched contents", result.contents().get(0).text());
        assertEquals("image", result.contents().get(1).type());
        assertEquals("image/png", result.contents().get(1).mimeType());
    }

    @Test
    void testListAndReadResources() throws Exception {
        testInitializeHandshake();

        // 1. listResources
        JsonNode listResult = mapper.readTree("""
                {
                  "resources": [
                    {
                      "uri": "memo://notes/today",
                      "name": "Today's Notes",
                      "description": "Quick daily notes",
                      "mimeType": "text/markdown"
                    }
                  ]
                }
                """);
        transport.prepareResponse(JsonRpcResponse.success(2L, listResult));

        List<McpResource> resources = client.listResources(Duration.ofSeconds(2));
        assertEquals(1, resources.size());
        assertEquals("memo://notes/today", resources.get(0).uri());
        assertEquals("Today's Notes", resources.get(0).name());

        // 2. readResource
        JsonNode readResult = mapper.readTree("""
                {
                  "contents": [
                    {
                      "uri": "memo://notes/today",
                      "mimeType": "text/markdown",
                      "text": "# Meeting at 10am"
                    }
                  ]
                }
                """);
        transport.prepareResponse(JsonRpcResponse.success(3L, readResult));

        List<McpResourceContent> contents = client.readResource("memo://notes/today", null, Duration.ofSeconds(2));
        assertEquals(1, contents.size());
        assertEquals("memo://notes/today", contents.get(0).uri());
        assertEquals("# Meeting at 10am", contents.get(0).text());
    }

    @Test
    void testNotificationListChanged() throws Exception {
        AtomicBoolean toolChanged = new AtomicBoolean(false);
        client.onToolsListChanged(() -> toolChanged.set(true));

        transport.triggerNotification(JsonRpcNotification.of(McpConstants.NOTIFICATION_TOOLS_LIST_CHANGED, mapper.createObjectNode()));
        assertTrue(toolChanged.get());
    }
}
