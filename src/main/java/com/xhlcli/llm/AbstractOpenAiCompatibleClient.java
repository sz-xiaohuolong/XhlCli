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
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

abstract class AbstractOpenAiCompatibleClient implements LlmClient {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    protected final ChatConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper;
    @SuppressWarnings("unused")
    private final Sleeper sleeper;
    private final DiagnosticSink diagnostics;
    private final OpenAiSseParser parser;

    AbstractOpenAiCompatibleClient(
            ChatConfig config,
            OkHttpClient httpClient,
            ObjectMapper mapper,
            Sleeper sleeper,
            DiagnosticSink diagnostics) {
        this.config = Objects.requireNonNull(config, "config");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.parser = new OpenAiSseParser(mapper);
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
        if (!config.hasApiKey()) {
            throw new LlmException(LlmErrorType.MISSING_CONFIGURATION,
                    providerName().toUpperCase(java.util.Locale.ROOT) + "_API_KEY is not configured.", false, false);
        }
        if (messages.isEmpty()) {
            throw new LlmException(LlmErrorType.INVALID_CONFIGURATION,
                    "At least one chat message is required.", false, false);
        }
        if (cancellationToken.isCancelled()) {
            throw new LlmException(LlmErrorType.CANCELLED, "The request was cancelled.", false, false);
        }

        diagnostics.debug("request.start", Map.of("provider", providerName(), "model", config.model()));
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

    private Request buildRequest(List<ChatMessage> messages, List<ToolDefinition> tools) throws LlmException {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", config.model());
        root.put("stream", true);
        ArrayNode messageNodes = root.putArray("messages");
        for (ChatMessage message : messages) {
            ObjectNode node = messageNodes.addObject();
            node.put("role", message.role().wireName());
            if (message.role() == ChatMessage.Role.ASSISTANT && message.content().isEmpty()) {
                node.putNull("content");
            } else {
                node.put("content", message.content());
            }
            if (!message.toolCalls().isEmpty()) {
                ArrayNode toolCalls = node.putArray("tool_calls");
                for (ToolCall call : message.toolCalls()) {
                    ObjectNode toolCall = toolCalls.addObject();
                    toolCall.put("id", call.id());
                    toolCall.put("type", "function");
                    ObjectNode function = toolCall.putObject("function");
                    function.put("name", call.name());
                    function.put("arguments", call.argumentsJson());
                }
            }
            if (message.role() == ChatMessage.Role.TOOL) {
                node.put("tool_call_id", message.toolCallId());
            }
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
                    .url(endpoint(config.baseUrl().toString()))
                    .header("Authorization", "Bearer " + config.apiKey())
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(mapper.writeValueAsString(root), JSON))
                    .build();
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw new LlmException(LlmErrorType.INVALID_CONFIGURATION,
                    "Unable to create a valid provider request.", false, false, failure);
        }
    }

    private String endpoint(String baseUrl) {
        String normalized = baseUrl;
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.endsWith("/chat/completions")
                ? normalized
                : normalized + "/chat/completions";
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
                detail = SecretRedactor.redact(body.string(), config.apiKey());
            } catch (IOException ignored) {
                detail = "";
            }
        }
        diagnostics.debug("request.http_error", Map.of("provider", providerName(), "status", Integer.toString(status)));
        LlmErrorType type = status == 401 || status == 403
                ? LlmErrorType.AUTHENTICATION
                : status == 429
                ? LlmErrorType.RATE_LIMIT
                : status >= 500
                ? LlmErrorType.SERVER
                : LlmErrorType.INVALID_RESPONSE;
        boolean retryable = status == 429 || status >= 500;
        String suffix = detail.isBlank() ? "" : " " + detail;
        throw new LlmException(type, "Provider request failed with HTTP " + status + "." + suffix,
                retryable, false);
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
                    "The provider request timed out.", true, partialResponse, failure);
        }
        return new LlmException(LlmErrorType.NETWORK,
                "Unable to reach the provider.", true, partialResponse, failure);
    }

    @Override
    public abstract String providerName();

    @Override
    public String modelName() {
        return config.model();
    }

    @Override
    public abstract ModelCapabilities capabilities();

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }
}
