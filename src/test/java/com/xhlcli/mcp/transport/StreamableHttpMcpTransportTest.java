package com.xhlcli.mcp.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.mcp.protocol.JsonRpcRequest;
import com.xhlcli.mcp.protocol.JsonRpcResponse;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class StreamableHttpMcpTransportTest {

    private MockWebServer server;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void testSendRequestWithHeadersAndReceiveSuccess() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"jsonrpc":"2.0","id":100,"result":{"tools":[{"name":"test_tool"}]}}
                        """));

        String url = server.url("/mcp").toString();
        Map<String, String> headers = Map.of("Authorization", "Bearer test-secret-token");

        StreamableHttpMcpTransport transport = new StreamableHttpMcpTransport(url, headers, null, mapper);
        transport.start();
        assertTrue(transport.isAlive());

        JsonRpcRequest req = JsonRpcRequest.of(100, "tools/list", mapper.createObjectNode());
        CompletableFuture<JsonRpcResponse> future = transport.sendRequest(req, null);

        JsonRpcResponse resp = future.get(3, TimeUnit.SECONDS);
        assertNotNull(resp);
        assertFalse(resp.isError());
        assertEquals(100L, ((Number) resp.id()).longValue());
        assertTrue(resp.result().path("tools").isArray());

        RecordedRequest recorded = server.takeRequest();
        assertEquals("POST", recorded.getMethod());
        assertEquals("/mcp", recorded.getPath());
        assertEquals("Bearer test-secret-token", recorded.getHeader("Authorization"));

        transport.close();
    }

    @Test
    void testHttpErrorMappedToJsonRpcError() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error"));

        String url = server.url("/mcp").toString();
        StreamableHttpMcpTransport transport = new StreamableHttpMcpTransport(url, Map.of(), null, mapper);
        transport.start();

        JsonRpcRequest req = JsonRpcRequest.of(101, "ping", null);
        CompletableFuture<JsonRpcResponse> future = transport.sendRequest(req, null);

        JsonRpcResponse resp = future.get(3, TimeUnit.SECONDS);
        assertNotNull(resp);
        assertTrue(resp.isError());
        assertEquals(500, resp.error().code());
        assertTrue(resp.error().message().contains("500"));

        transport.close();
    }
    @Test
    void closeCancelsOwnedClientRequests() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE));
        var transport = new StreamableHttpMcpTransport(server.url("/mcp").toString(), Map.of(), null, mapper);
        transport.start();
        var pending = transport.sendRequest(JsonRpcRequest.of(102, "ping", null), null);
        assertNotNull(server.takeRequest(3, TimeUnit.SECONDS));
        transport.close();
        assertThrows(java.util.concurrent.ExecutionException.class, () -> pending.get(3, TimeUnit.SECONDS));
        assertFalse(transport.isAlive());
    }

    @Test
    void closePreservesBorrowedClient() throws Exception {
        var client = new okhttp3.OkHttpClient();
        try {
            var transport = new StreamableHttpMcpTransport(server.url("/mcp").toString(), Map.of(), client, mapper);
            transport.start();
            transport.close();
            server.enqueue(new MockResponse().setBody("共享客户端仍可使用"));
            try (var response = client.newCall(new okhttp3.Request.Builder().url(server.url("/other")).build()).execute()) {
                assertEquals("共享客户端仍可使用", response.body().string());
            }
            assertFalse(client.dispatcher().executorService().isShutdown());
        } finally {
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        }
    }

}
