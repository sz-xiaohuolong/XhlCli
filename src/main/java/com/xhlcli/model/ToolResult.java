package com.xhlcli.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Locale;
import java.util.Objects;

public record ToolResult(
        String callId,
        String toolName,
        ToolResultStatus status,
        String summary,
        JsonNode data,
        long elapsedMillis,
        boolean truncated,
        int originalChars,
        String continueHint) {
    public ToolResult {
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(summary, "summary");
        data = Objects.requireNonNull(data, "data").deepCopy();
        if (elapsedMillis < 0) {
            throw new IllegalArgumentException("elapsedMillis must not be negative");
        }
        if (originalChars < 0) {
            throw new IllegalArgumentException("originalChars must not be negative");
        }
        continueHint = Objects.requireNonNull(continueHint, "continueHint");
    }

    @Override
    public JsonNode data() {
        return data.deepCopy();
    }

    public String observationJson(ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper");
        ObjectNode observation = mapper.createObjectNode();
        observation.put("status", status.name().toLowerCase(Locale.ROOT));
        observation.put("summary", summary);
        observation.set("data", data);
        observation.put("elapsed_ms", elapsedMillis);
        observation.put("truncated", truncated);
        observation.put("original_chars", originalChars);
        observation.put("continue_hint", continueHint);
        try {
            return mapper.writeValueAsString(observation);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Unable to serialize tool observation", failure);
        }
    }
}
