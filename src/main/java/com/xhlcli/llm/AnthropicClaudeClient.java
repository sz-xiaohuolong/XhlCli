package com.xhlcli.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhlcli.config.ChatConfig;
import com.xhlcli.config.SecretRedactor;
import com.xhlcli.model.ChatMessage;
import com.xhlcli.model.ChatResponse;
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

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AnthropicClaudeClient implements LlmClient {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    public static final String DEFAULT_MODEL = "claude-3-5-sonnet-20241022";
    public static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final String apiKey;
    private final String model;
    private final URI baseUrl;
    private final ModelCapabilities capabilities;
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper;
    private final Sleeper sleeper;
    private final DiagnosticSink diagnostics;
    private final AnthropicSseParser parser;

    public AnthropicClaudeClient(String apiKey, String model, URI baseUrl, DiagnosticSink diagnostics) {
        this(apiKey,
                model == null || model.isBlank() ? DEFAULT_MODEL : model,
                baseUrl == null ? URI.create(DEFAULT_BASE_URL) : baseUrl,
                ModelCapabilities.claudeDefault(),
                defaultHttpClient(),
                new ObjectMapper(),
                Thread::sleep,
                diagnostics);
    }

    public AnthropicClaudeClient(ChatConfig config, DiagnosticSink diagnostics) {
        this(config.apiKey(),
                config.model() == null || config.model().isBlank() ? DEFAULT_MODEL : config.model(),
                config.baseUrl() == null ? URI.create(DEFAULT_BASE_URL) : config.baseUrl(),
                ModelCapabilities.claudeDefault(),
                clientFor(config),
                new ObjectMapper(),
                Thread::sleep,
                diagnostics);
    }

    AnthropicClaudeClient(
            String apiKey,
            String model,
            URI baseUrl,
            ModelCapabilities capabilities,
            OkHttpClient httpClient,
            ObjectMapper mapper,
            Sleeper sleeper,
            DiagnosticSink diagnostics) {
        this.apiKey = apiKey;
        this.model = Objects.requireNonNull(model, "model");
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.parser = new AnthropicSseParser(mapper);
    }

    private static OkHttpClient defaultHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(300))
                .callTimeout(Duration.ofSeconds(600))
                .protocols(List.of(Protocol.HTTP_1_1))
                .build();
    }

    private static OkHttpClient clientFor(ChatConfig config) {
        return new OkHttpClient.Builder()
                .connectTimeout(config.connectTimeout())
                .readTimeout(config.readTimeout())
                .callTimeout(config.requestTimeout())
                .protocols(List.of(Protocol.HTTP_1_1))
                .build();
    }

    @Override
    public String providerName() {
        return "anthropic";
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

        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmException(LlmErrorType.MISSING_CONFIGURATION,
                    "ANTHROPIC_API_KEY is not configured.", false, false);
        }
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
            ChatResponse result = parser.parse(response.body(), listener, cancellationToken);
            diagnostics.debug("request.complete", Map.of("provider", providerName()));
            return result;
        } catch (IOException failure) {
            throw mapIoFailure(failure, cancellationToken, receivedDelta.get());
        }
    }

    private Request buildRequest(List<ChatMessage> messages, List<ToolDefinition> tools) throws LlmException {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", 4096);
        root.put("stream", true);

        // Anthropic: 顶级 system 参数
        List<String> systemTexts = new ArrayList<>();
        for (ChatMessage message : messages) {
            if (message.role() == ChatMessage.Role.SYSTEM && !message.content().isBlank()) {
                systemTexts.add(message.content());
            }
        }
        if (!systemTexts.isEmpty()) {
            root.put("system", String.join("\n\n", systemTexts));
        }

        // messages: 转换非 system 消息
        ArrayNode messageNodes = root.putArray("messages");
        for (ChatMessage message : messages) {
            if (message.role() == ChatMessage.Role.SYSTEM) {
                continue;
            }
            if (message.role() == ChatMessage.Role.USER) {
                ObjectNode node = messageNodes.addObject();
                node.put("role", "user");
                node.put("content", message.content());
            } else if (message.role() == ChatMessage.Role.TOOL) {
                // Anthropic: 工具结果是带有 tool_result 块的 user 消息
                ObjectNode node = messageNodes.addObject();
                node.put("role", "user");
                ArrayNode contentArr = node.putArray("content");
                ObjectNode toolResult = contentArr.addObject();
                toolResult.put("type", "tool_result");
                toolResult.put("tool_use_id", message.toolCallId() != null ? message.toolCallId() : "");
                toolResult.put("content", message.content());
            } else if (message.role() == ChatMessage.Role.ASSISTANT) {
                ObjectNode node = messageNodes.addObject();
                node.put("role", "assistant");
                if (message.toolCalls().isEmpty()) {
                    node.put("content", message.content());
                } else {
                    ArrayNode contentArr = node.putArray("content");
                    if (!message.content().isEmpty()) {
                        ObjectNode textBlock = contentArr.addObject();
                        textBlock.put("type", "text");
                        textBlock.put("text", message.content());
                    }
                    for (ToolCall call : message.toolCalls()) {
                        ObjectNode toolUse = contentArr.addObject();
                        toolUse.put("type", "tool_use");
                        toolUse.put("id", call.id());
                        toolUse.put("name", call.name());
                        try {
                            String args = call.argumentsJson();
                            if (args == null || args.isBlank()) {
                                toolUse.putObject("input");
                            } else {
                                toolUse.set("input", mapper.readTree(args));
                            }
                        } catch (Exception e) {
                            toolUse.putObject("input");
                        }
                    }
                }
            }
        }

        // Anthropic: input_schema 工具声明
        if (!tools.isEmpty()) {
            ArrayNode toolNodes = root.putArray("tools");
            for (ToolDefinition definition : tools) {
                ObjectNode tool = toolNodes.addObject();
                tool.put("name", definition.name());
                tool.put("description", definition.description());
                tool.set("input_schema", definition.parameters());
            }
        }

        try {
            return new Request.Builder()
                    .url(endpoint(baseUrl.toString()))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(mapper.writeValueAsString(root), JSON))
                    .build();
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw new LlmException(LlmErrorType.INVALID_CONFIGURATION,
                    "Unable to create a valid Anthropic request.", false, false, failure);
        }
    }

    private String endpoint(String baseUrlStr) {
        String normalized = baseUrlStr;
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.endsWith("/messages")
                ? normalized
                : normalized + "/v1/messages";
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
                detail = SecretRedactor.redact(body.string(), apiKey);
            } catch (IOException ignored) {
                detail = "";
            }
        }
        diagnostics.debug("request.http_error", Map.of("provider", providerName(), "status", Integer.toString(status)));
        LlmErrorType type = (status == 401 || status == 403)
                ? LlmErrorType.AUTHENTICATION
                : status == 429
                ? LlmErrorType.RATE_LIMIT
                : (status == 529 || status >= 500)
                ? LlmErrorType.SERVER
                : LlmErrorType.INVALID_RESPONSE;
        boolean retryable = (status == 429 || status == 529 || status >= 500);
        String suffix = detail.isBlank() ? "" : " " + detail;
        throw new LlmException(type, "Anthropic request failed with HTTP " + status + "." + suffix,
                retryable, false);
    }

    private boolean shouldRetry(LlmException failure, boolean receivedDelta, CancellationToken token) {
        if (receivedDelta || failure.partialResponse() || token.isCancelled()) {
            return false;
        }
        return failure.type() == LlmErrorType.NETWORK
                || failure.type() == LlmErrorType.RATE_LIMIT
                || failure.type() == LlmErrorType.SERVER;
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
            throw new LlmException(LlmErrorType.CANCELLED,
                    "The request was cancelled.", false, partialResponse);
        }
    }

    private LlmException mapIoFailure(IOException failure, CancellationToken token, boolean partialResponse) {
        if (token.isCancelled()) {
            diagnostics.debug("request.cancelled", Map.of("provider", providerName()));
            return new LlmException(LlmErrorType.CANCELLED,
                    "The request was cancelled.", false, partialResponse, failure);
        }
        if (failure instanceof SocketTimeoutException) {
            diagnostics.debug("request.timeout", Map.of("provider", providerName()));
            return new LlmException(LlmErrorType.TIMEOUT,
                    "The Anthropic request timed out.", true, partialResponse, failure);
        }
        return new LlmException(LlmErrorType.NETWORK,
                "Unable to reach Anthropic.", true, partialResponse, failure);
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }
}
