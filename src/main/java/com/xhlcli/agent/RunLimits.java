package com.xhlcli.agent;

import java.time.Duration;
import java.util.Objects;

/** Bounds one agent run before it can issue unbounded model or tool work. */
public record RunLimits(int maxIterations, Duration timeout) {
    private static final int MAX_ITERATIONS_LIMIT = 100;
    private static final Duration MAX_TIMEOUT = Duration.ofHours(1);

    public RunLimits {
        if (maxIterations <= 0 || maxIterations > MAX_ITERATIONS_LIMIT) {
            throw new IllegalArgumentException("maxIterations must be between 1 and 100");
        }
        timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("timeout must be greater than zero and at most one hour");
        }
    }
}
