package com.xhlcli.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonRpcProtocolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testRequestSerialization() throws Exception {
        JsonNode params = mapper.createObjectNode().put("name", "test");
        JsonRpcRequest req = JsonRpcRequest.of(1, "tools/call", params);

        String json = mapper.writeValueAsString(req);
        JsonNode node = mapper.readTree(json);

        assertEquals("2.0", node.path("jsonrpc").asText());
        assertEquals(1, node.path("id").asInt());
        assertEquals("tools/call", node.path("method").asText());
        assertEquals("test", node.path("params").path("name").asText());
    }

    @Test
    void testNotificationSerialization() throws Exception {
        JsonNode params = mapper.createObjectNode();
        JsonRpcNotification notif = JsonRpcNotification.of(McpConstants.NOTIFICATION_TOOLS_LIST_CHANGED, params);

        String json = mapper.writeValueAsString(notif);
        JsonNode node = mapper.readTree(json);

        assertEquals("2.0", node.path("jsonrpc").asText());
        assertFalse(node.has("id"));
        assertEquals(McpConstants.NOTIFICATION_TOOLS_LIST_CHANGED, node.path("method").asText());
    }

    @Test
    void testSuccessResponseDeserialization() throws Exception {
        String json = """
                {
                  "jsonrpc": "2.0",
                  "id": 42,
                  "result": {
                    "protocolVersion": "2024-11-05",
                    "serverInfo": { "name": "test-server", "version": "1.0" }
                  }
                }
                """;

        JsonRpcResponse resp = mapper.readValue(json, JsonRpcResponse.class);
        assertEquals("2.0", resp.jsonrpc());
        assertEquals(42, ((Number) resp.id()).intValue());
        assertFalse(resp.isError());
        assertNotNull(resp.result());
        assertEquals("2024-11-05", resp.result().path("protocolVersion").asText());
    }

    @Test
    void testErrorResponseDeserialization() throws Exception {
        String json = """
                {
                  "jsonrpc": "2.0",
                  "id": 99,
                  "error": {
                    "code": -32601,
                    "message": "Method not found"
                  }
                }
                """;

        JsonRpcResponse resp = mapper.readValue(json, JsonRpcResponse.class);
        assertEquals("2.0", resp.jsonrpc());
        assertEquals(99, ((Number) resp.id()).intValue());
        assertTrue(resp.isError());
        assertNotNull(resp.error());
        assertEquals(-32601, resp.error().code());
        assertEquals("Method not found", resp.error().message());
    }
}
