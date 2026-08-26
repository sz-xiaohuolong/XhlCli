package com.xhlcli.model;

public record TokenUsage(int inputTokens, int outputTokens, boolean known) {
    public TokenUsage {
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("Token counts must not be negative");
        }
    }

    public static TokenUsage unknown() {
        return new TokenUsage(0, 0, false);
    }
}
