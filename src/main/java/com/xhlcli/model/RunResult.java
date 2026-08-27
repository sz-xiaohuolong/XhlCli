package com.xhlcli.model;

import java.util.Objects;

public record RunResult(
        String runId,
        RunStatus status,
        String finalAnswer,
        String reason,
        int iterations,
        TokenUsage usage) {
    public RunResult {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(finalAnswer, "finalAnswer");
        Objects.requireNonNull(reason, "reason");
        if (iterations < 0) {
            throw new IllegalArgumentException("iterations must not be negative");
        }
        Objects.requireNonNull(usage, "usage");
    }
}
