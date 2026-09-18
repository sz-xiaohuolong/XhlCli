package com.xhlcli.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.model.ChatMessage;
import com.xhlcli.model.ChatResponse;
import com.xhlcli.model.TokenUsage;
import com.xhlcli.model.ToolDefinition;
import com.xhlcli.model.ToolMetadata;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class OllamaClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private MockWebServer server;

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
    void streamsNdjsonChunksAndAccumulatesToolCalls() throws Exception {
        String ndjson = """
                {"model":"qwen2.5-coder","created_at":"2026-09-18T00:00:00Z","message":{"role":"assistant","content":"Let me "},"done":false}
                {"model":"qwen2.5-coder","created_at":"2026-09-18T00:00:01Z","message":{"role":"assistant","content":"check."},"done":false}
                {"model":"qwen2.5-coder","created_at":"2026-09-18T00:00:02Z","message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"list_dir","arguments":{"path":"."}}}]},"done":true,"prompt_eval_count":25,"eval_count":14}
                """;

        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/x-ndjson")
                .setBody(ndjson));

        List<String> deltas = new ArrayList<>();
        OllamaClient client = new OllamaClient("qwen2.5-coder", server.url("/").uri(), DiagnosticSink.NO_OP);
        ChatResponse res = client.stream(
                List.of(ChatMessage.user("list files")),
                List.of(new ToolDefinition("list_dir", "List dir", mapper.createObjectNode(), ToolMetadata.conservative())),
                deltas::add,
                new CancellationToken()
        );

        assertEquals("Let me check.", res.content());
        assertEquals(List.of("Let me ", "check."), deltas);
        assertEquals(1, res.toolCalls().size());
        assertEquals("list_dir", res.toolCalls().get(0).name());
        assertTrue(res.toolCalls().get(0).argumentsJson().contains("\"path\":\".\""));
        assertEquals(new TokenUsage(25, 14, true), res.usage());

        RecordedRequest req = server.takeRequest();
        assertEquals("/api/chat", req.getPath());
        assertNull(req.getHeader("Authorization")); // No key required for Ollama
    }

    @Test
    void handlesServerErrorAndRetries() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("Internal Server Error"));
        server.enqueue(new MockResponse().setResponseCode(500).setBody("Internal Server Error"));

        OllamaClient client = new OllamaClient("qwen2.5-coder", server.url("/").uri(), DiagnosticSink.NO_OP);
        LlmException ex = assertThrows(LlmException.class, () ->
                client.stream(List.of(ChatMessage.user("hello")), delta -> {}, new CancellationToken()));

        assertEquals(LlmErrorType.SERVER, ex.type());
        assertTrue(ex.retryable());
    }
}
