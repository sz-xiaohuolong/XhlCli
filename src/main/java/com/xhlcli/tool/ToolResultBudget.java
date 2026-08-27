package com.xhlcli.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhlcli.model.ToolResult;

import java.util.Objects;

/** Keeps observations structurally valid when a tool result would exceed its character budget. */
public final class ToolResultBudget {
    public static final int DEFAULT_MAX_CHARS = 8_000;
    private static final String CONTINUE_HINT = "Retry with a narrower request to read more of this result.";
    private static final String COMPACT_CONTINUE_HINT = "Use a narrower request.";
    private static final String COMPACT_SUMMARY = "Result truncated.";

    private final int maxChars;
    private final ObjectMapper mapper;

    public ToolResultBudget(int maxChars, ObjectMapper mapper) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be positive");
        }
        this.maxChars = maxChars;
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public ToolResult apply(ToolResult result) {
        Objects.requireNonNull(result, "result");
        int originalChars = serializedLength(result.data());
        ToolResult withSize = new ToolResult(
                result.callId(), result.toolName(), result.status(), result.summary(), result.data(), result.elapsedMillis(),
                result.truncated(), originalChars, result.continueHint());
        if (withSize.observationJson(mapper).length() <= maxChars) {
            return withSize;
        }

        String source = safeSerialize(result.data());
        ToolResult truncated = truncatedResult(result, originalChars, source, source.length(), result.summary(), CONTINUE_HINT);
        for (boolean compact : new boolean[] {false, true}) {
            String summary = compact ? COMPACT_SUMMARY : result.summary();
            String continueHint = compact ? COMPACT_CONTINUE_HINT : CONTINUE_HINT;
            ToolResult emptyPreview = truncatedResult(result, originalChars, source, 0, summary, continueHint);
            truncated = emptyPreview;
            if (emptyPreview.observationJson(mapper).length() > maxChars) {
                continue;
            }
            int low = 0;
            int high = source.length();
            while (low <= high) {
                int previewLength = low + (high - low) / 2;
                ToolResult candidate = truncatedResult(result, originalChars, source, previewLength, summary, continueHint);
                if (candidate.observationJson(mapper).length() <= maxChars) {
                    truncated = candidate;
                    low = previewLength + 1;
                } else {
                    high = previewLength - 1;
                }
            }
            return truncated;
        }
        return truncated;
    }

    private ToolResult truncatedResult(
            ToolResult result, int originalChars, String source, int previewLength, String summary, String continueHint) {
        ObjectNode preview = mapper.createObjectNode();
        preview.put("preview", source.substring(0, previewLength));
        return new ToolResult(
                result.callId(), result.toolName(), result.status(), summary, preview, result.elapsedMillis(), true,
                originalChars, continueHint);
    }

    private int serializedLength(com.fasterxml.jackson.databind.JsonNode data) {
        return safeSerialize(data).length();
    }

    private String safeSerialize(com.fasterxml.jackson.databind.JsonNode data) {
        try {
            return mapper.writeValueAsString(data);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Unable to serialize tool result data", failure);
        }
    }
}
