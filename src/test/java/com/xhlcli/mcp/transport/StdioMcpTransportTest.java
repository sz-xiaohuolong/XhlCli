package com.xhlcli.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.mcp.protocol.JsonRpcNotification;
import com.xhlcli.mcp.protocol.JsonRpcRequest;
import com.xhlcli.mcp.protocol.JsonRpcResponse;
import com.xhlcli.mcp.protocol.McpConstants;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class StdioMcpTransportTest {

    private final ObjectMapper mapper = new ObjectMapper();

    static class FakeProcess extends Process {
        private final PipedOutputStream toProcessStdin = new PipedOutputStream();
        private final PipedInputStream fromProcessStdin;
        private final PipedOutputStream toProcessStdout = new PipedOutputStream();
        private final PipedInputStream fromProcessStdout;
        private final ByteArrayInputStream processStderr = new ByteArrayInputStream("fake stderr line\n".getBytes(StandardCharsets.UTF_8));
        private final AtomicBoolean alive = new AtomicBoolean(true);

        FakeProcess() throws IOException {
            fromProcessStdin = new PipedInputStream(toProcessStdin);
            fromProcessStdout = new PipedInputStream(toProcessStdout);
        }

        @Override
        public OutputStream getOutputStream() {
            return toProcessStdin;
        }

        @Override
        public InputStream getInputStream() {
            return fromProcessStdout;
        }

        @Override
        public InputStream getErrorStream() {
            return processStderr;
        }

        @Override
        public int waitFor() {
            alive.set(false);
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            alive.set(false);
            return true;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
            alive.set(false);
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        void replyToStdout(String line) throws IOException {
            toProcessStdout.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            toProcessStdout.flush();
        }
    }

    @Test
    void testSendRequestAndReceiveResponse() throws Exception {
        FakeProcess fakeProcess = new FakeProcess();
        StdioMcpTransport transport = new StdioMcpTransport(fakeProcess, mapper);
        transport.start();

        assertTrue(transport.isAlive());

        JsonNode params = mapper.createObjectNode().put("protocolVersion", McpConstants.PROTOCOL_VERSION);
        JsonRpcRequest req = JsonRpcRequest.of(1, McpConstants.METHOD_INITIALIZE, params);

        CompletableFuture<JsonRpcResponse> future = transport.sendRequest(req, null);

        // Simulate server sending back response via stdout
        String responseJson = """
                {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","serverInfo":{"name":"test","version":"1.0"}}}
                """;
        fakeProcess.replyToStdout(responseJson);

        JsonRpcResponse resp = future.get(2, TimeUnit.SECONDS);
        assertNotNull(resp);
        assertEquals(1L, ((Number) resp.id()).longValue());
        assertFalse(resp.isError());
        assertEquals("2024-11-05", resp.result().path("protocolVersion").asText());

        transport.close();
        assertFalse(transport.isAlive());
    }

    @Test
    void testStdoutNonProtocolPollutionBuffered() throws Exception {
        FakeProcess fakeProcess = new FakeProcess();
        StdioMcpTransport transport = new StdioMcpTransport(fakeProcess, mapper);
        transport.start();

        // Simulate server printing non-json debug log to stdout
        fakeProcess.replyToStdout("DEBUG: server starting up...");
        fakeProcess.replyToStdout("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"ok\":true}}");

        CompletableFuture<JsonRpcResponse> future = transport.sendRequest(JsonRpcRequest.of(2, "ping", null), null);
        JsonRpcResponse resp = future.get(2, TimeUnit.SECONDS);
        assertNotNull(resp);
        assertTrue(resp.result().path("ok").asBoolean());

        Thread.sleep(100);
        List<String> logs = transport.getRecentLogs();
        assertTrue(logs.stream().anyMatch(l -> l.contains("DEBUG: server starting up...")));

        transport.close();
    }

    @Test
    void testInboundNotificationHandled() throws Exception {
        FakeProcess fakeProcess = new FakeProcess();
        StdioMcpTransport transport = new StdioMcpTransport(fakeProcess, mapper);

        AtomicReference<JsonRpcNotification> receivedNotif = new AtomicReference<>();
        transport.setNotificationListener(receivedNotif::set);
        transport.start();

        fakeProcess.replyToStdout("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\",\"params\":{}}");

        // Wait a moment for reader thread
        for (int i = 0; i < 20; i++) {
            if (receivedNotif.get() != null) {
                break;
            }
            Thread.sleep(50);
        }

        assertNotNull(receivedNotif.get());
        assertEquals("notifications/tools/list_changed", receivedNotif.get().method());

        transport.close();
    }
}
