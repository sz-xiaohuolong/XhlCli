package com.xhlcli.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.model.ChatResponse;
import com.xhlcli.model.TokenUsage;
import okhttp3.ResponseBody;
import okio.BufferedSource;

import java.io.IOException;
import java.util.Objects;

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
        int inputTokens = 0;
        int outputTokens = 0;
        boolean usageKnown = false;
        boolean done = false;

        while (!source.exhausted()) {
            if (cancellationToken.isCancelled()) {
                throw new LlmException(LlmErrorType.CANCELLED, "The request was cancelled.", false,
                        !content.isEmpty());
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
            } catch (JsonProcessingException failure) {
                throw invalid("The provider returned malformed streaming data.", !content.isEmpty(), failure);
            }
        }

        if (!done) {
            throw invalid("The provider stream ended before completion.", !content.isEmpty(), null);
        }
        if (content.toString().isBlank()) {
            throw invalid("The provider returned no assistant text.", false, null);
        }
        TokenUsage usage = usageKnown
                ? new TokenUsage(inputTokens, outputTokens, true)
                : TokenUsage.unknown();
        return new ChatResponse(content.toString(), usage);
    }

    private LlmException invalid(String message, boolean partial, Throwable cause) {
        return new LlmException(LlmErrorType.INVALID_RESPONSE, message, false, partial, cause);
    }
}
