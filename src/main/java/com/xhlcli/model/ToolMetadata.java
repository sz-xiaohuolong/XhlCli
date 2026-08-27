package com.xhlcli.model;

import java.util.Objects;

public record ToolMetadata(
        RiskLevel riskLevel,
        boolean readOnly,
        boolean cancellable,
        boolean allowsParallel,
        String resourceKey) {

    public ToolMetadata {
        Objects.requireNonNull(riskLevel, "riskLevel");
        resourceKey = Objects.requireNonNull(resourceKey, "resourceKey");
    }

    public static ToolMetadata conservative() {
        return new ToolMetadata(RiskLevel.HIGH, false, false, false, "");
    }

    public enum RiskLevel {
        LOW,
        HIGH
    }
}
