package com.xhlcli.agent;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhlcli.model.ToolCall;
import com.xhlcli.model.ToolResult;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Detects three consecutive tool iterations with no observable progress. */
public final class RepetitionGuard {
    private static final int REPETITION_LIMIT = 3;

    private final ObjectMapper mapper;
    private String previousFingerprint;
    private int consecutiveCount;

    public RepetitionGuard(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public boolean recordCompletedIteration(List<ToolCall> calls, List<ToolResult> results) {
        Objects.requireNonNull(calls, "calls");
        Objects.requireNonNull(results, "results");
        if (calls.size() != results.size()) {
            throw new IllegalArgumentException("calls and results must have the same size");
        }

        String fingerprint = fingerprint(calls, results);
        if (fingerprint.equals(previousFingerprint)) {
            consecutiveCount++;
        } else {
            previousFingerprint = fingerprint;
            consecutiveCount = 1;
        }
        return consecutiveCount >= REPETITION_LIMIT;
    }

    private String fingerprint(List<ToolCall> calls, List<ToolResult> results) {
        ArrayNode iterations = mapper.createArrayNode();
        for (int index = 0; index < calls.size(); index++) {
            ToolCall call = Objects.requireNonNull(calls.get(index), "call");
            ToolResult result = Objects.requireNonNull(results.get(index), "result");
            ObjectNode entry = mapper.createObjectNode();
            entry.put("tool_name", Objects.requireNonNull(call.name(), "call.name"));
            entry.set("arguments", canonicalArguments(call.argumentsJson()));
            entry.put("status", result.status().name());
            entry.put("summary", result.summary());
            entry.set("data", canonicalize(result.data()));
            entry.put("truncated", result.truncated());
            entry.put("original_chars", result.originalChars());
            entry.put("continue_hint", result.continueHint());
            iterations.add(entry);
        }
        try {
            return mapper.writeValueAsString(iterations);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Unable to serialize repetition fingerprint", failure);
        }
    }

    private JsonNode canonicalArguments(String argumentsJson) {
        if (argumentsJson == null) {
            return malformedArguments(null);
        }
        try (JsonParser parser = mapper.getFactory().createParser(argumentsJson)) {
            JsonToken firstToken = parser.nextToken();
            if (firstToken == null) {
                return malformedArguments(argumentsJson);
            }
            JsonNode parsed = mapper.readTree(parser);
            if (parsed == null || parsed.isMissingNode() || parser.nextToken() != null) {
                return malformedArguments(argumentsJson);
            }
            ArrayNode tagged = mapper.createArrayNode();
            tagged.add(true);
            tagged.add(canonicalize(parsed));
            return tagged;
        } catch (IOException failure) {
            return malformedArguments(argumentsJson);
        }
    }

    private ArrayNode malformedArguments(String rawArguments) {
        ArrayNode tagged = mapper.createArrayNode();
        tagged.add(false);
        if (rawArguments == null) {
            tagged.addNull();
        } else {
            tagged.add(rawArguments);
        }
        return tagged;
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = mapper.createObjectNode();
            Map<String, JsonNode> fields = new TreeMap<>();
            node.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((name, value) -> sorted.set(name, canonicalize(value)));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode ordered = mapper.createArrayNode();
            node.forEach(value -> ordered.add(canonicalize(value)));
            return ordered;
        }
        return node.deepCopy();
    }
}
