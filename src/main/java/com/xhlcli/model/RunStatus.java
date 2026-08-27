package com.xhlcli.model;

public enum RunStatus {
    CREATED,
    THINKING,
    CALLING_TOOL,
    OBSERVING,
    COMPLETED,
    FAILED,
    CANCELED,
    LIMIT_REACHED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELED || this == LIMIT_REACHED;
    }
}
