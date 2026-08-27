package com.xhlcli.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.model.ChatResponse;
import com.xhlcli.model.TokenUsage;
import com.xhlcli.model.ToolCall;
import okhttp3.ResponseBody;
import okio.BufferedSource;

import java.io.IOException;
import java.util.Objects;
import java.util.Map;
import java.util.TreeMap;

final class OpenAiSseParser {
    private final ObjectMapper mapper;

    OpenAiSseParser(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    ChatResponse parse(
            ResponseBody responseBody,
            StreamListener listener,
            CancellationToken cancellationToken) throws IOException, LlmException {
        if (responseBody == null) {
            throw invalid("The provider returned an empty response body.", false, null);
        }

        BufferedSource source = responseBody.source();
        StringBuilder content = new StringBuilder();
        Map<Integer, ToolCallBuilder> toolCalls = new TreeMap<>();
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
            if (!trimmed.startsWith("data:")) {
                continue;
            }
            String payload = trimmed.substring("data:".length()).trim();
            if (payload.isEmpty()) {
                continue;
            }
            if ("[DONE]".equals(payload)) {
                done = true;
                break;
            }

            try {
                JsonNode root = mapper.readTree(payload);
                JsonNode usage = root.path("usage");
                if (usage.has("prompt_tokens") && usage.has("completion_tokens")) {
                    inputTokens = usage.path("prompt_tokens").asInt();
                    outputTokens = usage.path("completion_tokens").asInt();
                    usageKnown = true;
                }

                JsonNode choices = root.path("choices");
                if (!choices.isArray() || choices.isEmpty()) {
                    continue;
                }
                JsonNode delta = choices.get(0).path("delta");
                String text = delta.path("content").asText("");
                if (!text.isEmpty()) {
                    content.append(text);
                    listener.onTextDelta(text);
                }
                JsonNode toolCallDeltas = delta.path("tool_calls");
                if (toolCallDeltas.isArray()) {
                    for (JsonNode toolCallDelta : toolCallDeltas) {
                        if (!toolCallDelta.has("index") || !toolCallDelta.path("index").canConvertToInt()) {
                            throw invalid("The provider returned a tool call without an index.",
                                    hasPartialResponse(content, toolCalls), null);
                        }
                        ToolCallBuilder builder = toolCalls.computeIfAbsent(
                                toolCallDelta.path("index").asInt(), ignored -> new ToolCallBuilder());
                        builder.appendId(toolCallDelta.path("id"));
                        JsonNode function = toolCallDelta.path("function");
                        builder.appendName(function.path("name"));
                        builder.appendArguments(function.path("arguments"));
                    }
                }
            } catch (JsonProcessingException failure) {
                throw invalid("The provider returned malformed streaming data.",
                        hasPartialResponse(content, toolCalls), failure);
            }
        }

        if (!done) {
            throw invalid("The provider stream ended before completion.", hasPartialResponse(content, toolCalls), null);
        }
        if (content.isEmpty() && toolCalls.isEmpty()) {
            throw new LlmException(LlmErrorType.EMPTY_RESPONSE,
                    "The provider returned no assistant text or tool calls.", false, false);
        }
        TokenUsage usage = usageKnown
                ? new TokenUsage(inputTokens, outputTokens, true)
                : TokenUsage.unknown();
        try {
            return new ChatResponse(content.toString(), toolCalls.values().stream()
                    .map(ToolCallBuilder::build)
                    .toList(), usage);
        } catch (IllegalStateException failure) {
            throw invalid("The provider returned an incomplete tool call.", hasPartialResponse(content, toolCalls), failure);
        }
    }

    private LlmException invalid(String message, boolean partial, Throwable cause) {
        return new LlmException(LlmErrorType.INVALID_RESPONSE, message, false, partial, cause);
    }

    private boolean hasPartialResponse(StringBuilder content, Map<Integer, ToolCallBuilder> toolCalls) {
        return !content.isEmpty() || !toolCalls.isEmpty();
    }

    private static final class ToolCallBuilder {
        private final StringBuilder id = new StringBuilder();
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();

        void appendId(JsonNode value) {
            append(value, id);
        }

        void appendName(JsonNode value) {
            append(value, name);
        }

        void appendArguments(JsonNode value) {
            append(value, arguments);
        }

        ToolCall build() {
            if (id.isEmpty() || name.isEmpty() || arguments.isEmpty()) {
                throw new IllegalStateException("Tool call fields must not be empty");
            }
            return new ToolCall(id.toString(), name.toString(), arguments.toString());
        }

        private void append(JsonNode value, StringBuilder target) {
            if (!value.isMissingNode() && !value.isNull()) {
                target.append(value.asText());
            }
        }
    }
}
