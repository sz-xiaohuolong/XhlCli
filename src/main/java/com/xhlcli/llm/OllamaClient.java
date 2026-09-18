package com.xhlcli.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhlcli.model.ChatMessage;
import com.xhlcli.model.ChatResponse;
import com.xhlcli.model.TokenUsage;
import com.xhlcli.model.ToolCall;
import com.xhlcli.model.ToolDefinition;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class OllamaClient implements LlmClient {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    public static final String DEFAULT_MODEL = "qwen2.5-coder";
    public static final String DEFAULT_BASE_URL = "http://localhost:11434";

    private final String model;
    private final URI baseUrl;
    private final ModelCapabilities capabilities;
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper;
    private final Sleeper sleeper;
    private final DiagnosticSink diagnostics;

    public OllamaClient(String model, URI baseUrl, DiagnosticSink diagnostics) {
        this(model == null || model.isBlank() ? DEFAULT_MODEL : model,
                baseUrl == null ? URI.create(DEFAULT_BASE_URL) : baseUrl,
                ModelCapabilities.ollamaDefault(),
                defaultHttpClient(),
                new ObjectMapper(),
                Thread::sleep,
                diagnostics);
    }

    OllamaClient(
            String model,
            URI baseUrl,
            ModelCapabilities capabilities,
            OkHttpClient httpClient,
            ObjectMapper mapper,
            Sleeper sleeper,
            DiagnosticSink diagnostics) {
        this.model = Objects.requireNonNull(model, "model");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    private static OkHttpClient defaultHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(300))
                .callTimeout(Duration.ofSeconds(600))
                .protocols(List.of(Protocol.HTTP_1_1))
                .build();
    }

    @Override
    public String providerName() {
        return "ollama";
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public ModelCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public ChatResponse stream(
            List<ChatMessage> messages,
            List<ToolDefinition> tools,
            StreamListener listener,
            CancellationToken cancellationToken) throws LlmException {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(cancellationToken, "cancellationToken");

        if (messages.isEmpty()) {
            throw new LlmException(LlmErrorType.INVALID_CONFIGURATION,
                    "At least one chat message is required.", false, false);
        }
        if (cancellationToken.isCancelled()) {
            throw new LlmException(LlmErrorType.CANCELLED, "The request was cancelled.", false, false);
        }

        diagnostics.debug("request.start", Map.of("provider", providerName(), "model", model));
        AtomicBoolean receivedDelta = new AtomicBoolean();
        StreamListener trackingListener = delta -> {
            receivedDelta.set(true);
            listener.onTextDelta(delta);
        };

        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return executeAttempt(messages, tools, trackingListener, cancellationToken, receivedDelta);
            } catch (LlmException failure) {
                if (attempt > 0 || !shouldRetry(failure, receivedDelta.get(), cancellationToken)) {
                    throw failure;
                }
                diagnostics.debug("request.retry", Map.of(
                        "provider", providerName(),
                        "reason", failure.type().name(),
                        "attempt", "2"));
                waitBeforeRetry(cancellationToken, receivedDelta.get());
            }
        }
        throw new IllegalStateException("Retry loop exhausted without a result");
    }

    private ChatResponse executeAttempt(
            List<ChatMessage> messages,
            List<ToolDefinition> tools,
            StreamListener listener,
            CancellationToken cancellationToken,
            AtomicBoolean receivedDelta) throws LlmException {
        Request request = buildRequest(messages, tools);
        Call call = httpClient.newCall(request);
        try (CancellationToken.Registration ignored = cancellationToken.onCancel(call::cancel);
             Response response = call.execute()) {
            requireSuccessful(response);
            ChatResponse result = parseNdjson(response.body(), listener, cancellationToken);
            diagnostics.debug("request.complete", Map.of("provider", providerName()));
            return result;
        } catch (IOException failure) {
            throw mapIoFailure(failure, cancellationToken, receivedDelta.get());
        }
    }

    private Request buildRequest(List<ChatMessage> messages, List<ToolDefinition> tools) throws LlmException {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("stream", true);

        ArrayNode messageNodes = root.putArray("messages");
        for (ChatMessage message : messages) {
            ObjectNode node = messageNodes.addObject();
            node.put("role", message.role().wireName());
            node.put("content", message.content());
        }

        if (!tools.isEmpty()) {
            ArrayNode toolNodes = root.putArray("tools");
            for (ToolDefinition definition : tools) {
                ObjectNode tool = toolNodes.addObject();
                tool.put("type", "function");
                ObjectNode function = tool.putObject("function");
                function.put("name", definition.name());
                function.put("description", definition.description());
                function.set("parameters", definition.parameters());
            }
        }

        try {
            return new Request.Builder()
                    .url(endpoint(baseUrl.toString()))
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(mapper.writeValueAsString(root), JSON))
                    .build();
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw new LlmException(LlmErrorType.INVALID_CONFIGURATION,
                    "Unable to create a valid Ollama request.", false, false, failure);
        }
    }

    private String endpoint(String baseUrlStr) {
        String normalized = baseUrlStr;
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.endsWith("/api/chat")
                ? normalized
                : normalized + "/api/chat";
    }

    private ChatResponse parseNdjson(
            ResponseBody responseBody,
            StreamListener listener,
            CancellationToken cancellationToken) throws IOException, LlmException {
        if (responseBody == null) {
            throw new LlmException(LlmErrorType.INVALID_RESPONSE, "The provider returned an empty response body.", false, false);
        }

        BufferedSource source = responseBody.source();
        StringBuilder content = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        int inputTokens = 0;
        int outputTokens = 0;
        boolean usageKnown = false;
        boolean done = false;

        while (!source.exhausted()) {
            if (cancellationToken.isCancelled()) {
                throw new LlmException(LlmErrorType.CANCELLED, "The request was cancelled.", false,
                        hasPartialResponse(content, toolCalls));
            }
            String line = source.readUtf8Line();
            if (line == null) {
                break;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            try {
                JsonNode root = mapper.readTree(trimmed);
                if (root.has("message")) {
                    JsonNode msgNode = root.path("message");
                    String textDelta = msgNode.path("content").asText("");
                    if (!textDelta.isEmpty()) {
                        content.append(textDelta);
                        listener.onTextDelta(textDelta);
                    }

                    JsonNode toolCallsNode = msgNode.path("tool_calls");
                    if (toolCallsNode.isArray()) {
                        for (JsonNode tc : toolCallsNode) {
                            JsonNode fn = tc.path("function");
                            String name = fn.path("name").asText("");
                            String id = tc.has("id") ? tc.path("id").asText() : "call_" + UUID.randomUUID().toString().substring(0, 8);
                            JsonNode args = fn.path("arguments");
                            String argsJson = args.isTextual() ? args.asText() : mapper.writeValueAsString(args);
                            toolCalls.add(new ToolCall(id, name, argsJson));
                        }
                    }
                }

                if (root.path("done").asBoolean(false)) {
                    done = true;
                    if (root.has("prompt_eval_count")) {
                        inputTokens = root.path("prompt_eval_count").asInt();
                        usageKnown = true;
                    }
                    if (root.has("eval_count")) {
                        outputTokens = root.path("eval_count").asInt();
                        usageKnown = true;
                    }
                }
            } catch (JsonProcessingException e) {
                throw new LlmException(LlmErrorType.INVALID_RESPONSE, "Failed to parse Ollama NDJSON chunk.",
                        false, hasPartialResponse(content, toolCalls), e);
            }
        }

        if (!done && !hasPartialResponse(content, toolCalls)) {
            throw new LlmException(LlmErrorType.INVALID_RESPONSE, "Ollama stream ended prematurely.", false, false);
        }
        if (content.toString().isBlank() && toolCalls.isEmpty()) {
            throw new LlmException(LlmErrorType.EMPTY_RESPONSE, "Ollama returned no content or tool calls.", false, false);
        }

        TokenUsage usage = usageKnown
                ? new TokenUsage(inputTokens, outputTokens, true)
                : TokenUsage.unknown();
        return new ChatResponse(content.toString(), toolCalls, usage);
    }

    private boolean hasPartialResponse(StringBuilder content, List<ToolCall> toolCalls) {
        return !content.isEmpty() || !toolCalls.isEmpty();
    }

    private void requireSuccessful(Response response) throws LlmException {
        if (response.isSuccessful()) {
            return;
        }
        int status = response.code();
        String detail = "";
        ResponseBody body = response.body();
        if (body != null) {
            try {
                detail = body.string();
            } catch (IOException ignored) {}
        }
        diagnostics.debug("request.http_error", Map.of("provider", providerName(), "status", Integer.toString(status)));
        LlmErrorType type = (status == 404)
                ? LlmErrorType.INVALID_CONFIGURATION
                : (status >= 500)
                ? LlmErrorType.SERVER
                : LlmErrorType.INVALID_RESPONSE;
        boolean retryable = status >= 500;
        String suffix = detail.isBlank() ? "" : " " + detail;
        throw new LlmException(type, "Ollama request failed with HTTP " + status + "." + suffix, retryable, false);
    }

    private boolean shouldRetry(LlmException failure, boolean receivedDelta, CancellationToken token) {
        if (receivedDelta || failure.partialResponse() || token.isCancelled()) {
            return false;
        }
        return failure.type() == LlmErrorType.NETWORK || failure.type() == LlmErrorType.SERVER;
    }

    private void waitBeforeRetry(CancellationToken token, boolean partialResponse) throws LlmException {
        try {
            sleeper.sleep(Duration.ofMillis(250));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new LlmException(LlmErrorType.CANCELLED,
                    "The request was interrupted before retry.", false, partialResponse, failure);
        }
        if (token.isCancelled()) {
            throw new LlmException(LlmErrorType.CANCELLED, "The request was cancelled.", false, partialResponse);
        }
    }

    private LlmException mapIoFailure(IOException failure, CancellationToken token, boolean partialResponse) {
        if (token.isCancelled()) {
            diagnostics.debug("request.cancelled", Map.of("provider", providerName()));
            return new LlmException(LlmErrorType.CANCELLED, "The request was cancelled.", false, partialResponse, failure);
        }
        if (failure instanceof SocketTimeoutException) {
            diagnostics.debug("request.timeout", Map.of("provider", providerName()));
            return new LlmException(LlmErrorType.TIMEOUT, "The Ollama request timed out.", true, partialResponse, failure);
        }
        return new LlmException(LlmErrorType.NETWORK, "Unable to reach Ollama at " + baseUrl + ". Is ollama serve running?", true, partialResponse, failure);
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }
}
