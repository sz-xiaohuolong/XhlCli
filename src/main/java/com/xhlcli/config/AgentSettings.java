package com.xhlcli.config;

import java.time.Duration;
import java.util.Objects;

/** Immutable, bounded limits for one Phase 02 ReAct run. */
public record AgentSettings(int maxIterations, Duration timeout) {
    public static final int DEFAULT_MAX_ITERATIONS = 10;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(600);

    public AgentSettings {
        if (maxIterations < 1 || maxIterations > 100) {
            throw new IllegalArgumentException("maxIterations must be between 1 and 100");
        }
        timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.compareTo(Duration.ofSeconds(1)) < 0 || timeout.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("timeout must be between 1 and 3600 seconds");
        }
    }

    public static AgentSettings defaults() {
        return new AgentSettings(DEFAULT_MAX_ITERATIONS, DEFAULT_TIMEOUT);
    }
}
