package com.xhlcli.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.config.ChatConfig;
import com.xhlcli.config.LogLevel;
import com.xhlcli.model.ChatMessage;
import com.xhlcli.model.ChatResponse;
import com.xhlcli.model.TokenUsage;
import com.xhlcli.model.ToolCall;
import com.xhlcli.model.ToolDefinition;
import com.xhlcli.model.ToolMetadata;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private MockWebServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stopServer() throws Exception {
        server.shutdown();
    }

    @Test
    void streamsTextAndPreservesTheOpenAiRequestContract() throws Exception {
        server.enqueue(sse("""
                data: {"choices":[{"delta":{"role":"assistant","content":"你"}}]}

                data: {"choices":[{"delta":{"content":"好"}}]}

                data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":7,"completion_tokens":2}}

                data: [DONE]

                """));
        List<ChatMessage> messages = List.of(
                new ChatMessage(ChatMessage.Role.SYSTEM, "You are helpful."),
                new ChatMessage(ChatMessage.Role.USER, "你好"));

        List<String> deltas = new ArrayList<>();
        ChatResponse response;
        try (DeepSeekClient client = new DeepSeekClient(config(Duration.ofSeconds(2)), DiagnosticSink.NO_OP)) {
            response = client.stream(messages, deltas::add, new CancellationToken());
        }

        assertEquals(List.of("你", "好"), deltas);
        assertEquals("你好", response.content());
        assertEquals(new TokenUsage(7, 2, true), response.usage());

        RecordedRequest request = server.takeRequest();
        assertEquals("/v1/chat/completions", request.getPath());
        assertEquals("Bearer test-key", request.getHeader("Authorization"));
        JsonNode body = mapper.readTree(request.getBody().readUtf8());
        assertEquals("test-model", body.path("model").asText());
        assertTrue(body.path("stream").asBoolean());
        assertEquals("system", body.path("messages").get(0).path("role").asText());
        assertEquals("You are helpful.", body.path("messages").get(0).path("content").asText());
        assertEquals("user", body.path("messages").get(1).path("role").asText());
        assertEquals("你好", body.path("messages").get(1).path("content").asText());
        assertEquals(3, body.size());
    }

    @Test
    void serializesToolsAndToolProtocolMessagesInRequestOrder() throws Exception {
        server.enqueue(sse(ok("ready")));
        ToolDefinition echoText = new ToolDefinition(
                "echo_text",
                "Echo text",
                mapper.createObjectNode().put("type", "object"),
                ToolMetadata.conservative());
        List<ChatMessage> messages = List.of(
                ChatMessage.user("echo hello"),
                ChatMessage.assistant("", List.of(new ToolCall("call_1", "echo_text", "{\"text\":\"hello\"}"))),
                ChatMessage.tool("call_1", "{\"status\":\"success\"}"));

        try (DeepSeekClient client = client(config(Duration.ofSeconds(2)))) {
            client.stream(messages, List.of(echoText), ignored -> {}, new CancellationToken());
        }

        JsonNode body = mapper.readTree(server.takeRequest().getBody().readUtf8());
        assertEquals("function", body.path("tools").get(0).path("type").asText());
        JsonNode function = body.path("tools").get(0).path("function");
        assertEquals("echo_text", function.path("name").asText());
        assertEquals("Echo text", function.path("description").asText());
        assertEquals("object", function.path("parameters").path("type").asText());

        JsonNode assistant = body.path("messages").get(1);
        assertTrue(assistant.path("content").isNull());
        assertEquals("call_1", assistant.path("tool_calls").get(0).path("id").asText());
        assertEquals("function", assistant.path("tool_calls").get(0).path("type").asText());
        assertEquals("echo_text", assistant.path("tool_calls").get(0).path("function").path("name").asText());
        assertEquals("{\"text\":\"hello\"}",
                assistant.path("tool_calls").get(0).path("function").path("arguments").asText());

        JsonNode observation = body.path("messages").get(2);
        assertEquals("tool", observation.path("role").asText());
        assertEquals("call_1", observation.path("tool_call_id").asText());
        assertEquals("{\"status\":\"success\"}", observation.path("content").asText());
    }

    @Test
    void accumulatesFragmentedToolCallsAcrossDeltas() throws Exception {
        server.enqueue(sse("""
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_","function":{"name":"echo_","arguments":"{\\\"text\\\":"}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"1","function":{"name":"text","arguments":"\\\"hello\\\"}"}}]}}]}

                data: [DONE]

                """));

        ChatResponse response = stream(client(config(Duration.ofSeconds(2))));

        assertEquals("", response.content());
        assertEquals(List.of(new ToolCall("call_1", "echo_text", "{\"text\":\"hello\"}")), response.toolCalls());
    }

    @Test
    void accumulatesTextAndMultipleIndexedToolCalls() throws Exception {
        server.enqueue(sse("""
                data: {"choices":[{"delta":{"content":"Checking ","tool_calls":[{"index":1,"id":"call_2","function":{"name":"current_","arguments":"{}"}},{"index":0,"id":"call_1","function":{"name":"echo_","arguments":"{\\\"text\\\":\\\"hi\\\"}"}}]}}]}

                data: {"choices":[{"delta":{"content":"now.","tool_calls":[{"index":1,"function":{"name":"time"}},{"index":0,"function":{"name":"text"}}]}}]}

                data: [DONE]

                """));

        ChatResponse response = stream(client(config(Duration.ofSeconds(2))));

        assertEquals("Checking now.", response.content());
        assertEquals(List.of(
                new ToolCall("call_1", "echo_text", "{\"text\":\"hi\"}"),
                new ToolCall("call_2", "current_time", "{}")), response.toolCalls());
    }

    @Test
    void doneStreamWithoutTextOrToolCallsIsAnEmptyResponse() throws Exception {
        server.enqueue(sse("data: [DONE]\n\n"));

        LlmException failure = assertThrows(LlmException.class,
                () -> stream(client(config(Duration.ofSeconds(2)))));

        assertEquals(LlmErrorType.EMPTY_RESPONSE, failure.type());
    }

    @Test
    void doneStreamWithOnlyBlankTextIsAnEmptyResponse() throws Exception {
        server.enqueue(sse("""
                data: {"choices":[{"delta":{"content":" \\t"}}]}

                data: [DONE]

                """));

        LlmException failure = assertThrows(LlmException.class,
                () -> stream(client(config(Duration.ofSeconds(2)))));

        assertEquals(LlmErrorType.EMPTY_RESPONSE, failure.type());
    }

    @Test
    void authenticationFailureDoesNotRetryAndRedactsTheApiKey() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(401)
                .setBody("invalid credential test-key"));

        LlmException failure = assertThrows(LlmException.class, () -> stream(client(config(Duration.ofSeconds(2)))));

        assertEquals(LlmErrorType.AUTHENTICATION, failure.type());
        assertFalse(failure.retryable());
        assertFalse(failure.getMessage().contains("test-key"));
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void rateLimitRetriesOnceThenSucceeds() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(429).setBody("slow down"));
        server.enqueue(sse(ok("after retry")));

        ChatResponse response = stream(client(config(Duration.ofSeconds(2))));

        assertEquals("after retry", response.content());
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void serverFailureRetriesOnlyOnce() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("unavailable"));
        server.enqueue(new MockResponse().setResponseCode(503).setBody("still unavailable"));

        LlmException failure = assertThrows(LlmException.class, () -> stream(client(config(Duration.ofSeconds(2)))));

        assertEquals(LlmErrorType.SERVER, failure.type());
        assertTrue(failure.retryable());
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void malformedJsonIsAnInvalidResponse() throws Exception {
        server.enqueue(sse("data: {not-json}\n\ndata: [DONE]\n\n"));

        LlmException failure = assertThrows(LlmException.class, () -> stream(client(config(Duration.ofSeconds(2)))));

        assertEquals(LlmErrorType.INVALID_RESPONSE, failure.type());
        assertFalse(failure.partialResponse());
    }

    @Test
    void truncatedStreamMarksAnAlreadyPrintedAnswerAsPartial() throws Exception {
        server.enqueue(sse("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"));

        List<String> deltas = new ArrayList<>();
        LlmException failure;
        try (DeepSeekClient client = client(config(Duration.ofSeconds(2)))) {
            failure = assertThrows(LlmException.class,
                    () -> client.stream(userMessage(), deltas::add, new CancellationToken()));
        }

        assertEquals(List.of("partial"), deltas);
        assertEquals(LlmErrorType.INVALID_RESPONSE, failure.type());
        assertTrue(failure.partialResponse());
    }

    @Test
    void ignoresUnknownSseFieldsAndReasoningContent() throws Exception {
        server.enqueue(sse("""
                event: vendor-event
                data: {"vendor":"ignored"}

                data: {"choices":[{"delta":{"reasoning_content":"private reasoning"}}]}

                data: {"choices":[{"delta":{"content":"visible"}}]}

                data: [DONE]

                """));

        ChatResponse response = stream(client(config(Duration.ofSeconds(2))));

        assertEquals("visible", response.content());
    }

    @Test
    void delayedBodyMapsToTimeout() throws Exception {
        server.enqueue(sse(ok("late"))
                .setBodyDelay(300, TimeUnit.MILLISECONDS));

        LlmException failure = assertThrows(LlmException.class,
                () -> stream(client(config(Duration.ofMillis(100)))));

        assertEquals(LlmErrorType.TIMEOUT, failure.type());
    }

    @Test
    void cancellingAnInFlightCallMapsToCancelled() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        CancellationToken token = new CancellationToken();
        try (DeepSeekClient client = client(config(Duration.ofSeconds(5)));
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<ChatResponse> future = executor.submit(() -> client.stream(userMessage(), ignored -> {}, token));
            server.takeRequest(2, TimeUnit.SECONDS);

            token.cancel();

            ExecutionException wrapper = assertThrows(ExecutionException.class,
                    () -> future.get(2, TimeUnit.SECONDS));
            LlmException failure = (LlmException) wrapper.getCause();
            assertEquals(LlmErrorType.CANCELLED, failure.type());
        }
    }

    @Test
    void responseWithoutUsageReturnsUnknownUsage() throws Exception {
        server.enqueue(sse(ok("no usage")));

        ChatResponse response = stream(client(config(Duration.ofSeconds(2))));

        assertEquals(TokenUsage.unknown(), response.usage());
    }

    @Test
    void doesNotDuplicateAnExistingChatCompletionsPath() throws Exception {
        server.enqueue(sse(ok("path")));
        ChatConfig config = config(Duration.ofSeconds(2), "/v1/chat/completions");

        stream(client(config));

        assertEquals("/v1/chat/completions", server.takeRequest().getPath());
    }

    @Test
    void aPreDeltaNetworkFailureRetriesOnce() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        server.enqueue(sse(ok("recovered")));

        ChatResponse response = stream(client(config(Duration.ofSeconds(2))));

        assertEquals("recovered", response.content());
        assertEquals(2, server.getRequestCount());
    }

    private ChatConfig config(Duration readTimeout) {
        return config(readTimeout, "/v1");
    }

    private ChatConfig config(Duration readTimeout, String path) {
        return new ChatConfig(
                "test-key",
                "test-model",
                server.url(path).uri(),
                Duration.ofSeconds(2),
                readTimeout,
                Duration.ofSeconds(5),
                LogLevel.WARN,
                Map.of());
    }

    private DeepSeekClient client(ChatConfig config) {
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(config.connectTimeout())
                .readTimeout(config.readTimeout())
                .callTimeout(config.requestTimeout())
                .protocols(List.of(Protocol.HTTP_1_1))
                .build();
        return new DeepSeekClient(config, httpClient, mapper, ignored -> {}, DiagnosticSink.NO_OP);
    }

    private ChatResponse stream(DeepSeekClient client) throws Exception {
        try (client) {
            return client.stream(userMessage(), ignored -> {}, new CancellationToken());
        }
    }

    private List<ChatMessage> userMessage() {
        return List.of(new ChatMessage(ChatMessage.Role.USER, "hello"));
    }

    private String ok(String content) {
        return "data: {\"choices\":[{\"delta\":{\"content\":\"" + content + "\"}}]}\n\n"
                + "data: [DONE]\n\n";
    }

    private MockResponse sse(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body);
    }
}
