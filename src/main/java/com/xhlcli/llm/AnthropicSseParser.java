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
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

final class AnthropicSseParser {
    private final ObjectMapper mapper;

    AnthropicSseParser(ObjectMapper mapper) {
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
        Map<Integer, AnthropicToolCallBuilder> toolCalls = new TreeMap<>();
        int inputTokens = 0;
        int outputTokens = 0;
        boolean usageKnown = false;
        boolean done = false;
        String currentEvent = null;

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
                currentEvent = null;
                continue;
            }
            if (trimmed.startsWith(":")) {
                continue;
            }
            if (trimmed.startsWith("event:")) {
                currentEvent = trimmed.substring("event:".length()).trim();
                continue;
            }
            if (!trimmed.startsWith("data:")) {
                continue;
            }
            String payload = trimmed.substring("data:".length()).trim();
            if (payload.isEmpty()) {
                continue;
            }

            try {
                JsonNode root = mapper.readTree(payload);
                String type = root.path("type").asText("");
                if (type.isEmpty() && currentEvent != null) {
                    type = currentEvent;
                }

                switch (type) {
                    case "message_start" -> {
                        JsonNode message = root.path("message");
                        JsonNode usage = message.path("usage");
                        if (usage.has("input_tokens")) {
                            inputTokens = usage.path("input_tokens").asInt();
                            usageKnown = true;
                        }
                    }
                    case "content_block_start" -> {
                        int index = root.path("index").asInt();
                        JsonNode block = root.path("content_block");
                        String blockType = block.path("type").asText("");
                        if ("tool_use".equals(blockType)) {
                            AnthropicToolCallBuilder builder = toolCalls.computeIfAbsent(
                                    index, ignored -> new AnthropicToolCallBuilder());
                            builder.setId(block.path("id").asText(""));
                            builder.setName(block.path("name").asText(""));
                        }
                    }
                    case "content_block_delta" -> {
                        int index = root.path("index").asInt();
                        JsonNode delta = root.path("delta");
                        String deltaType = delta.path("type").asText("");
                        if ("text_delta".equals(deltaType)) {
                            String text = delta.path("text").asText("");
                            if (!text.isEmpty()) {
                                content.append(text);
                                listener.onTextDelta(text);
                            }
                        } else if ("input_json_delta".equals(deltaType)) {
                            String partialJson = delta.path("partial_json").asText("");
                            AnthropicToolCallBuilder builder = toolCalls.computeIfAbsent(
                                    index, ignored -> new AnthropicToolCallBuilder());
                            builder.appendArguments(partialJson);
                        }
                    }
                    case "content_block_stop" -> {
                        // block done
                    }
                    case "message_delta" -> {
                        JsonNode usage = root.path("usage");
                        if (usage.has("output_tokens")) {
                            outputTokens = usage.path("output_tokens").asInt();
                            usageKnown = true;
                        }
                    }
                    case "message_stop" -> {
                        done = true;
                    }
                    case "ping" -> {
                        // heartbeat
                    }
                    case "error" -> {
                        JsonNode errorNode = root.path("error");
                        String errType = errorNode.path("type").asText("unknown_error");
                        String errMsg = errorNode.path("message").asText("Anthropic error: " + payload);
                        LlmErrorType mappedType = switch (errType) {
                            case "authentication_error", "permission_error" -> LlmErrorType.AUTHENTICATION;
                            case "rate_limit_error" -> LlmErrorType.RATE_LIMIT;
                            case "overloaded_error" -> LlmErrorType.SERVER;
                            case "invalid_request_error" -> LlmErrorType.INVALID_CONFIGURATION;
                            default -> LlmErrorType.INVALID_RESPONSE;
                        };
                        boolean retryable = mappedType == LlmErrorType.RATE_LIMIT || mappedType == LlmErrorType.SERVER;
                        throw new LlmException(mappedType, errMsg, retryable, hasPartialResponse(content, toolCalls));
                    }
                    default -> {
                        // ignore unknown future events safely
                    }
                }
            } catch (JsonProcessingException failure) {
                throw invalid("The provider returned malformed streaming data.",
                        hasPartialResponse(content, toolCalls), failure);
            }
        }

        if (!done && !hasPartialResponse(content, toolCalls)) {
            throw invalid("The provider stream ended before completion.", false, null);
        }
        if (content.toString().isBlank() && toolCalls.isEmpty()) {
            throw new LlmException(LlmErrorType.EMPTY_RESPONSE,
                    "The provider returned no assistant text or tool calls.", false, false);
        }

        TokenUsage usage = usageKnown
                ? new TokenUsage(inputTokens, outputTokens, true)
                : TokenUsage.unknown();
        try {
            return new ChatResponse(content.toString(), toolCalls.values().stream()
                    .map(AnthropicToolCallBuilder::build)
                    .toList(), usage);
        } catch (IllegalStateException failure) {
            throw invalid("The provider returned an incomplete tool call.", hasPartialResponse(content, toolCalls), failure);
        }
    }

    private boolean hasPartialResponse(StringBuilder content, Map<Integer, AnthropicToolCallBuilder> toolCalls) {
        return !content.isEmpty() || !toolCalls.isEmpty();
    }

    private LlmException invalid(String message, boolean partialResponse, Throwable cause) {
        return new LlmException(LlmErrorType.INVALID_RESPONSE, message, false, partialResponse, cause);
    }

    private static final class AnthropicToolCallBuilder {
        private String id = "";
        private String name = "";
        private final StringBuilder arguments = new StringBuilder();

        void setId(String id) {
            this.id = id;
        }

        void setName(String name) {
            this.name = name;
        }

        void appendArguments(String partialJson) {
            if (partialJson != null) {
                arguments.append(partialJson);
            }
        }

        ToolCall build() {
            return new ToolCall(id, name, arguments.toString());
        }
    }
}
