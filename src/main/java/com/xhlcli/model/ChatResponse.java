package com.xhlcli.model;

import java.util.List;
import java.util.Objects;

public record ChatResponse(String content, List<ToolCall> toolCalls, TokenUsage usage) {
    public ChatResponse {
        Objects.requireNonNull(content, "content");
        toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls"));
        Objects.requireNonNull(usage, "usage");
        if (content.isBlank() && toolCalls.isEmpty()) {
            throw new IllegalArgumentException("Responses must contain text or tool calls");
        }
    }

    public ChatResponse(String content, TokenUsage usage) {
        this(content, List.of(), usage);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
