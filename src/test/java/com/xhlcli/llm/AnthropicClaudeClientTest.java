package com.xhlcli.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.model.ChatMessage;
import com.xhlcli.model.ChatResponse;
import com.xhlcli.model.ToolCall;
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

public class AnthropicClaudeClientTest {
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
    void correctlyMapsMessagesAndToolSchema() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""
                        event: message_start
                        data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-3-5-sonnet-20241022","usage":{"input_tokens":50,"output_tokens":5}}}

                        event: content_block_start
                        data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                        event: content_block_delta
                        data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"File content received."}}

                        event: content_block_stop
                        data: {"type":"content_block_stop","index":0}

                        event: message_delta
                        data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}

                        event: message_stop
                        data: {"type":"message_stop"}

                        """));

        List<ChatMessage> conversation = List.of(
                ChatMessage.system("Rule 1: Always check file existence."),
                ChatMessage.system("Rule 2: Do not write empty files."),
                ChatMessage.user("Read sample.txt"),
                ChatMessage.assistant("", List.of(new ToolCall("call_abc", "read_file", "{\"path\":\"sample.txt\"}"))),
                ChatMessage.tool("call_abc", "Hello world from sample.txt")
        );

        ToolDefinition toolDef = new ToolDefinition(
                "read_file",
                "Reads a file",
                mapper.createObjectNode().put("type", "object"),
                ToolMetadata.conservative()
        );

        try (AnthropicClaudeClient client = new AnthropicClaudeClient(
                "claude-key", "claude-3-5-sonnet-20241022", server.url("/").uri(), DiagnosticSink.NO_OP)) {
            ChatResponse res = client.stream(conversation, List.of(toolDef), delta -> {}, new CancellationToken());
            assertEquals("File content received.", res.content());
        }

        RecordedRequest request = server.takeRequest();
        assertEquals("/v1/messages", request.getPath());
        assertEquals("claude-key", request.getHeader("x-api-key"));
        assertEquals("2023-06-01", request.getHeader("anthropic-version"));

        JsonNode root = mapper.readTree(request.getBody().readUtf8());
        // System message extraction
        assertEquals("Rule 1: Always check file existence.\n\nRule 2: Do not write empty files.", root.path("system").asText());

        // Tools input_schema
        JsonNode tools = root.path("tools");
        assertEquals(1, tools.size());
        assertEquals("read_file", tools.get(0).path("name").asText());
        assertEquals("Reads a file", tools.get(0).path("description").asText());
        assertTrue(tools.get(0).has("input_schema"));

        // Messages mapping
        JsonNode messages = root.path("messages");
        assertEquals(3, messages.size());

        // 1. User message
        assertEquals("user", messages.get(0).path("role").asText());
        assertEquals("Read sample.txt", messages.get(0).path("content").asText());

        // 2. Assistant tool use
        assertEquals("assistant", messages.get(1).path("role").asText());
        JsonNode assistantBlocks = messages.get(1).path("content");
        assertEquals(1, assistantBlocks.size());
        assertEquals("tool_use", assistantBlocks.get(0).path("type").asText());
        assertEquals("call_abc", assistantBlocks.get(0).path("id").asText());
        assertEquals("read_file", assistantBlocks.get(0).path("name").asText());
        assertEquals("sample.txt", assistantBlocks.get(0).path("input").path("path").asText());

        // 3. Tool result as user message
        assertEquals("user", messages.get(2).path("role").asText());
        JsonNode toolResultBlocks = messages.get(2).path("content");
        assertEquals(1, toolResultBlocks.size());
        assertEquals("tool_result", toolResultBlocks.get(0).path("type").asText());
        assertEquals("call_abc", toolResultBlocks.get(0).path("tool_use_id").asText());
        assertEquals("Hello world from sample.txt", toolResultBlocks.get(0).path("content").asText());
    }

    @Test
    void handlesAnthropicStreamingErrorsGracefully() {
        String errorPayload = """
                event: error
                data: {"type":"error","error":{"type":"overloaded_error","message":"Anthropic servers are overloaded."}}

                """;
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(errorPayload));
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(errorPayload));

        try (AnthropicClaudeClient client = new AnthropicClaudeClient(
                "claude-key", "claude-3-5-sonnet-20241022", server.url("/").uri(), DiagnosticSink.NO_OP)) {
            LlmException ex = assertThrows(LlmException.class, () ->
                    client.stream(List.of(ChatMessage.user("hi")), delta -> {}, new CancellationToken()));
            assertEquals(LlmErrorType.SERVER, ex.type());
            assertTrue(ex.retryable());
            assertTrue(ex.getMessage().contains("overloaded"));
        }
    }

    @Test
    void handlesNonRetryableAnthropicError() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""
                        event: error
                        data: {"type":"error","error":{"type":"invalid_request_error","message":"max_tokens exceeds limit."}}

                        """));

        try (AnthropicClaudeClient client = new AnthropicClaudeClient(
                "claude-key", "claude-3-5-sonnet-20241022", server.url("/").uri(), DiagnosticSink.NO_OP)) {
            LlmException ex = assertThrows(LlmException.class, () ->
                    client.stream(List.of(ChatMessage.user("hi")), delta -> {}, new CancellationToken()));
            assertEquals(LlmErrorType.INVALID_CONFIGURATION, ex.type());
            assertFalse(ex.retryable());
            assertTrue(ex.getMessage().contains("max_tokens"));
        }
    }
}
