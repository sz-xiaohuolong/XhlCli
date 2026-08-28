package com.xhlcli.model;

import com.xhlcli.llm.LlmErrorType;

import java.time.Instant;
import java.util.Objects;

public sealed interface RunEvent permits RunEvent.RunStarted, RunEvent.ModelRequestStarted,
        RunEvent.TextDelta, RunEvent.ModelRequestCompleted, RunEvent.ToolStarted,
        RunEvent.ToolCompleted, RunEvent.IterationCompleted, RunEvent.RunCompleted,
        RunEvent.RunFailed, RunEvent.RunCancelled, RunEvent.RunLimitReached {

    Metadata metadata();

    record Metadata(String runId, long sequence, Instant timestamp, int iteration) {
        public Metadata {
            if (runId == null || runId.isBlank()) {
                throw new IllegalArgumentException("runId must not be blank");
            }
            if (sequence <= 0) {
                throw new IllegalArgumentException("sequence must be positive");
            }
            Objects.requireNonNull(timestamp, "timestamp");
            if (iteration < 0) {
                throw new IllegalArgumentException("iteration must not be negative");
            }
        }
    }

    record RunStarted(Metadata metadata, String inputSummary) implements RunEvent {
        public RunStarted {
            requireMetadata(metadata);
            Objects.requireNonNull(inputSummary, "inputSummary");
        }
    }

    record ModelRequestStarted(Metadata metadata) implements RunEvent {
        public ModelRequestStarted {
            requireMetadata(metadata);
        }
    }

    record TextDelta(Metadata metadata, String text) implements RunEvent {
        public TextDelta {
            requireMetadata(metadata);
            Objects.requireNonNull(text, "text");
        }
    }

    record ModelRequestCompleted(Metadata metadata, TokenUsage usage) implements RunEvent {
        public ModelRequestCompleted {
            requireMetadata(metadata);
            Objects.requireNonNull(usage, "usage");
        }
    }

    record ToolStarted(Metadata metadata, String toolName, String argumentsSummary) implements RunEvent {
        public ToolStarted {
            requireMetadata(metadata);
            requireNonBlank(toolName, "toolName");
            Objects.requireNonNull(argumentsSummary, "argumentsSummary");
        }
    }

    record ToolCompleted(
            Metadata metadata,
            String toolName,
            ToolResultStatus status,
            long elapsedMillis,
            String summary) implements RunEvent {
        public ToolCompleted {
            requireMetadata(metadata);
            requireNonBlank(toolName, "toolName");
            Objects.requireNonNull(status, "status");
            if (elapsedMillis < 0) {
                throw new IllegalArgumentException("elapsedMillis must not be negative");
            }
            Objects.requireNonNull(summary, "summary");
        }
    }

    record IterationCompleted(Metadata metadata, int toolCallCount) implements RunEvent {
        public IterationCompleted {
            requireMetadata(metadata);
            if (toolCallCount < 0) {
                throw new IllegalArgumentException("toolCallCount must not be negative");
            }
        }
    }

    record RunCompleted(Metadata metadata, String finalAnswer, TokenUsage usage) implements RunEvent {
        public RunCompleted {
            requireMetadata(metadata);
            requireNonBlank(finalAnswer, "finalAnswer");
            Objects.requireNonNull(usage, "usage");
        }

        public RunStatus status() {
            return RunStatus.COMPLETED;
        }
    }

    record RunFailed(
            Metadata metadata,
            String reason,
            LlmErrorType errorType,
            String safeMessage,
            boolean partialResponse) implements RunEvent {
        public RunFailed {
            requireMetadata(metadata);
            requireNonBlank(reason, "reason");
            Objects.requireNonNull(safeMessage, "safeMessage");
            if (errorType == null) {
                if (!safeMessage.isEmpty() || partialResponse) {
                    throw new IllegalArgumentException("Non-LLM failures must not contain LLM failure details");
                }
            } else {
                requireNonBlank(safeMessage, "safeMessage");
            }
        }

        public RunFailed(Metadata metadata, String reason) {
            this(metadata, reason, null, "", false);
        }

        public RunStatus status() {
            return RunStatus.FAILED;
        }
    }

    record RunCancelled(Metadata metadata, String reason) implements RunEvent {
        public RunCancelled {
            requireMetadata(metadata);
            requireNonBlank(reason, "reason");
        }

        public RunStatus status() {
            return RunStatus.CANCELED;
        }
    }

    record RunLimitReached(Metadata metadata, String reason) implements RunEvent {
        public RunLimitReached {
            requireMetadata(metadata);
            requireNonBlank(reason, "reason");
        }

        public RunStatus status() {
            return RunStatus.LIMIT_REACHED;
        }
    }

    private static void requireMetadata(Metadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
    }

    private static void requireNonBlank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
    }
}
