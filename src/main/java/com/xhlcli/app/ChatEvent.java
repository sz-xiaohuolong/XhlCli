package com.xhlcli.app;

import com.xhlcli.llm.LlmErrorType;
import com.xhlcli.model.TokenUsage;

import java.util.Objects;

public sealed interface ChatEvent {
    record Waiting() implements ChatEvent {}

    record TextDelta(String text) implements ChatEvent {
        public TextDelta {
            Objects.requireNonNull(text, "text");
        }
    }

    record Completed(TokenUsage usage) implements ChatEvent {
        public Completed {
            Objects.requireNonNull(usage, "usage");
        }
    }

    record Failed(LlmErrorType type, String message, boolean partial) implements ChatEvent {
        public Failed {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(message, "message");
        }
    }

    record Cancelled() implements ChatEvent {}
}
