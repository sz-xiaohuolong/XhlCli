package com.xhlcli.model;

import java.util.Objects;

public record ChatResponse(String content, TokenUsage usage) {
    public ChatResponse {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(usage, "usage");
    }
}
