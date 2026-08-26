package com.xhlcli.llm;

import java.util.Objects;

public final class LlmException extends Exception {
    private final LlmErrorType type;
    private final boolean retryable;
    private final boolean partialResponse;

    public LlmException(LlmErrorType type, String safeMessage, boolean retryable, boolean partialResponse) {
        this(type, safeMessage, retryable, partialResponse, null);
    }

    public LlmException(
            LlmErrorType type,
            String safeMessage,
            boolean retryable,
            boolean partialResponse,
            Throwable cause) {
        super(Objects.requireNonNull(safeMessage, "safeMessage"), cause);
        this.type = Objects.requireNonNull(type, "type");
        this.retryable = retryable;
        this.partialResponse = partialResponse;
    }

    public LlmErrorType type() {
        return type;
    }

    public boolean retryable() {
        return retryable;
    }

    public boolean partialResponse() {
        return partialResponse;
    }
}
