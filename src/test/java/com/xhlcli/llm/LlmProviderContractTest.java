package com.xhlcli.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.config.ChatConfig;
import com.xhlcli.config.LogLevel;
import com.xhlcli.model.*;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class LlmProviderContractTest {
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

    private ChatConfig createConfig(String model) {
        return new ChatConfig(
                "test-api-key",
                model,
                server.url("/").uri(),
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                LogLevel.INFO,
                Map.of()
        );
    }

    @Test
    void deepSeekAndOpenAiStreamTextContract() throws Exception {
        String openaiSse = """
                data: {"choices":[{"delta":{"content":"Hello "}}]}

                data: {"choices":[{"delta":{"content":"world!"}}]}

                data: {"choices":[{"delta":{}}],"usage":{"prompt_tokens":10,"completion_tokens":5}}

                data: [DONE]

                """;

        // Test DeepSeekClient
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(openaiSse));
        List<String> deepSeekDeltas = new ArrayList<>();
        try (DeepSeekClient client = new DeepSeekClient(createConfig("deepseek-chat"), DiagnosticSink.NO_OP)) {
            ChatResponse res = client.stream(List.of(ChatMessage.user("hi")), deepSeekDeltas::add, new CancellationToken());
            assertEquals("Hello world!", res.content());
            assertEquals(List.of("Hello ", "world!"), deepSeekDeltas);
            assertEquals(new TokenUsage(10, 5, true), res.usage());
            assertEquals("deepseek", client.providerName());
            assertTrue(client.capabilities().supportsTools());
        }

        RecordedRequest req1 = server.takeRequest();
        assertEquals("/chat/completions", req1.getPath());
        assertEquals("Bearer test-api-key", req1.getHeader("Authorization"));

        // Test OpenAiClient
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(openaiSse));
        List<String> openaiDeltas = new ArrayList<>();
        try (OpenAiClient client = new OpenAiClient(createConfig("gpt-4o"), DiagnosticSink.NO_OP)) {
            ChatResponse res = client.stream(List.of(ChatMessage.user("hi")), openaiDeltas::add, new CancellationToken());
            assertEquals("Hello world!", res.content());
            assertEquals(List.of("Hello ", "world!"), openaiDeltas);
            assertEquals(new TokenUsage(10, 5, true), res.usage());
            assertEquals("openai", client.providerName());
            assertTrue(client.capabilities().supportsVision());
        }

        RecordedRequest req2 = server.takeRequest();
        assertEquals("/chat/completions", req2.getPath());
        assertEquals("Bearer test-api-key", req2.getHeader("Authorization"));
    }

    @Test
    void anthropicClaudeStreamTextContract() throws Exception {
        String anthropicSse = """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-3-5-sonnet-20241022","usage":{"input_tokens":12,"output_tokens":1}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Claude "}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"answers."}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":6}}

                event: message_stop
                data: {"type":"message_stop"}

                """;

        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(anthropicSse));
        List<String> deltas = new ArrayList<>();
        try (AnthropicClaudeClient client = new AnthropicClaudeClient(
                "claude-secret-key", "claude-3-5-sonnet-20241022", server.url("/").uri(), DiagnosticSink.NO_OP)) {
            ChatResponse res = client.stream(List.of(
                    ChatMessage.system("System instructions"),
                    ChatMessage.user("Hello")
            ), deltas::add, new CancellationToken());

            assertEquals("Claude answers.", res.content());
            assertEquals(List.of("Claude ", "answers."), deltas);
            assertEquals(new TokenUsage(12, 6, true), res.usage());
            assertEquals("anthropic", client.providerName());
            assertEquals("claude-3-5-sonnet-20241022", client.modelName());
            assertEquals(200_000, client.capabilities().maxContextWindow());
        }

        RecordedRequest req = server.takeRequest();
        assertEquals("/v1/messages", req.getPath());
        assertEquals("claude-secret-key", req.getHeader("x-api-key"));
        assertEquals("2023-06-01", req.getHeader("anthropic-version"));

        JsonNode body = mapper.readTree(req.getBody().readUtf8());
        assertEquals("System instructions", body.path("system").asText());
        assertEquals("claude-3-5-sonnet-20241022", body.path("model").asText());
        assertEquals(1, body.path("messages").size());
        assertEquals("user", body.path("messages").get(0).path("role").asText());
        assertEquals("Hello", body.path("messages").get(0).path("content").asText());
    }

    @Test
    void toolCallStreamingContractAcrossProviders() throws Exception {
        ToolDefinition tool = new ToolDefinition(
                "read_file",
                "Read file content",
                mapper.createObjectNode().put("type", "object"),
                ToolMetadata.conservative()
        );

        // 1. OpenAI format tool stream
        String openaiToolSse = """
                data: {"choices":[{"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"call_123","function":{"name":"read_file","arguments":""}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"path\\":\\""}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"foo.txt\\"}"}}]}}]}

                data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":15,"completion_tokens":10}}

                data: [DONE]

                """;

        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(openaiToolSse));
        try (OpenAiClient client = new OpenAiClient(createConfig("gpt-4o"), DiagnosticSink.NO_OP)) {
            ChatResponse res = client.stream(List.of(ChatMessage.user("read foo.txt")), List.of(tool), delta -> {}, new CancellationToken());
            assertEquals(1, res.toolCalls().size());
            ToolCall tc = res.toolCalls().get(0);
            assertEquals("call_123", tc.id());
            assertEquals("read_file", tc.name());
            assertEquals("{\"path\":\"foo.txt\"}", tc.argumentsJson());
        }

        // 2. Anthropic format tool stream
        String anthropicToolSse = """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_tool","type":"message","role":"assistant","model":"claude-3-5-sonnet-20241022","usage":{"input_tokens":18,"output_tokens":1}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_999","name":"read_file","input":{}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"path\\":\\""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"bar.txt\\"}"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":12}}

                event: message_stop
                data: {"type":"message_stop"}

                """;

        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(anthropicToolSse));
        try (AnthropicClaudeClient client = new AnthropicClaudeClient(
                "claude-secret-key", "claude-3-5-sonnet-20241022", server.url("/").uri(), DiagnosticSink.NO_OP)) {
            ChatResponse res = client.stream(List.of(ChatMessage.user("read bar.txt")), List.of(tool), delta -> {}, new CancellationToken());
            assertEquals(1, res.toolCalls().size());
            ToolCall tc = res.toolCalls().get(0);
            assertEquals("toolu_999", tc.id());
            assertEquals("read_file", tc.name());
            assertEquals("{\"path\":\"bar.txt\"}", tc.argumentsJson());
        }
    }

    @Test
    void errorMappingContractAcrossProviders() {
        // 401 Unauthorized
        server.enqueue(new MockResponse().setResponseCode(401).setBody("Invalid API key"));
        try (OpenAiClient client = new OpenAiClient(createConfig("gpt-4o"), DiagnosticSink.NO_OP)) {
            LlmException ex = assertThrows(LlmException.class, () ->
                    client.stream(List.of(ChatMessage.user("hi")), delta -> {}, new CancellationToken()));
            assertEquals(LlmErrorType.AUTHENTICATION, ex.type());
            assertFalse(ex.retryable());
        }

        server.enqueue(new MockResponse().setResponseCode(401).setBody("Unauthorized Anthropic key"));
        try (AnthropicClaudeClient client = new AnthropicClaudeClient(
                "bad-key", "claude-3-5-sonnet-20241022", server.url("/").uri(), DiagnosticSink.NO_OP)) {
            LlmException ex = assertThrows(LlmException.class, () ->
                    client.stream(List.of(ChatMessage.user("hi")), delta -> {}, new CancellationToken()));
            assertEquals(LlmErrorType.AUTHENTICATION, ex.type());
            assertFalse(ex.retryable());
        }

        // 429 Rate Limit (retries exhausted)
        server.enqueue(new MockResponse().setResponseCode(429).setBody("Rate limit"));
        server.enqueue(new MockResponse().setResponseCode(429).setBody("Rate limit"));
        try (AnthropicClaudeClient client = new AnthropicClaudeClient(
                "key", "claude-3-5-sonnet-20241022", server.url("/").uri(), DiagnosticSink.NO_OP)) {
            LlmException ex = assertThrows(LlmException.class, () ->
                    client.stream(List.of(ChatMessage.user("hi")), delta -> {}, new CancellationToken()));
            assertEquals(LlmErrorType.RATE_LIMIT, ex.type());
            assertTrue(ex.retryable());
        }
    }
}
