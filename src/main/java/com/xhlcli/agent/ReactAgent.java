package com.xhlcli.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.llm.LlmClient;
import com.xhlcli.llm.LlmErrorType;
import com.xhlcli.llm.LlmException;
import com.xhlcli.model.ChatMessage;
import com.xhlcli.model.ChatResponse;
import com.xhlcli.model.RunEvent;
import com.xhlcli.model.RunEventSink;
import com.xhlcli.model.RunResult;
import com.xhlcli.model.RunStatus;
import com.xhlcli.model.TokenUsage;
import com.xhlcli.model.ToolCall;
import com.xhlcli.model.ToolDefinition;
import com.xhlcli.model.ToolResult;
import com.xhlcli.tool.ToolExecutor;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Drives one serial structured-tool ReAct loop without committing partial protocol history. */
public final class ReactAgent implements AgentRunner {
    private final ChatMessage systemMessage;
    private final LlmClient client;
    private final ToolExecutor executor;
    private final List<ToolDefinition> toolDefinitions;
    private final RunLimits limits;
    private final TimeoutScheduler timeoutScheduler;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Supplier<String> runIdSupplier;
    private List<ChatMessage> committedHistory;

    public ReactAgent(
            ChatMessage systemMessage,
            LlmClient client,
            ToolExecutor executor,
            List<ToolDefinition> toolDefinitions,
            RunLimits limits,
            TimeoutScheduler timeoutScheduler,
            ObjectMapper mapper,
            Clock clock,
            Supplier<String> runIdSupplier) {
        this.systemMessage = Objects.requireNonNull(systemMessage, "systemMessage");
        if (systemMessage.role() != ChatMessage.Role.SYSTEM) {
            throw new IllegalArgumentException("systemMessage must have the SYSTEM role");
        }
        this.client = Objects.requireNonNull(client, "client");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.toolDefinitions = List.copyOf(Objects.requireNonNull(toolDefinitions, "toolDefinitions"));
        this.limits = Objects.requireNonNull(limits, "limits");
        this.timeoutScheduler = Objects.requireNonNull(timeoutScheduler, "timeoutScheduler");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.runIdSupplier = Objects.requireNonNull(runIdSupplier, "runIdSupplier");
        this.committedHistory = List.of(systemMessage);
    }

    @Override
    public synchronized RunResult run(String input, RunEventSink events, CancellationToken userCancellation) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(userCancellation, "userCancellation");
        String runId = requireRunId(runIdSupplier.get());
        RunLifecycle lifecycle = new RunLifecycle();
        EventSequencer sequencer = new EventSequencer(runId, events, clock);
        CancellationToken operationCancellation = new CancellationToken();
        AtomicReference<StopReason> stopReason = new AtomicReference<>(StopReason.NONE);
        List<ChatMessage> workingHistory = new ArrayList<>(committedHistory);
        TokenUsage usage = TokenUsage.unknown();
        boolean receivedUsage = false;
        int iterations = 0;

        try (CancellationToken.Registration userRegistration = userCancellation.onCancel(
                () -> requestStop(stopReason, StopReason.USER, operationCancellation));
             TimeoutScheduler.Registration timeoutRegistration = timeoutScheduler.schedule(limits.timeout(),
                     () -> requestStop(stopReason, StopReason.TIMEOUT, operationCancellation))) {
            sequencer.emit(new RunEvent.RunStarted(sequencer.metadata(0), summarize(input)));
            if (input.isBlank()) {
                return finish(lifecycle, sequencer, runId, RunStatus.FAILED, "INVALID_INPUT", "", iterations, usage);
            }
            if (stopReason.get() != StopReason.NONE) {
                return finishForStop(lifecycle, sequencer, runId, stopReason.get(), iterations, usage);
            }
            workingHistory.add(ChatMessage.user(input));
            RepetitionGuard repetitionGuard = new RepetitionGuard(mapper);
            Set<String> callIds = new HashSet<>();
            int emptyResponses = 0;

            while (true) {
                RunResult stopped = finishIfStopped(lifecycle, sequencer, runId, stopReason, iterations, usage);
                if (stopped != null) {
                    return stopped;
                }
                if (iterations >= limits.maxIterations()) {
                    return finish(lifecycle, sequencer, runId, RunStatus.LIMIT_REACHED, "MAX_ITERATIONS", "", iterations, usage);
                }
                if (lifecycle.status() != RunStatus.THINKING) {
                    lifecycle.transitionTo(RunStatus.THINKING);
                }
                lifecycle.requireActive();
                int modelIteration = iterations;
                sequencer.emit(new RunEvent.ModelRequestStarted(sequencer.metadata(iterations)));
                ChatResponse response;
                try {
                    response = client.stream(List.copyOf(workingHistory), toolDefinitions, delta -> {
                        if (!delta.isEmpty() && stopReason.get() == StopReason.NONE) {
                            lifecycle.requireActive();
                            sequencer.emit(new RunEvent.TextDelta(sequencer.metadata(modelIteration), delta));
                        }
                    }, operationCancellation);
                } catch (LlmException failure) {
                    RunResult stoppedAfterFailure = finishIfStopped(
                            lifecycle, sequencer, runId, stopReason, iterations, usage);
                    if (stoppedAfterFailure != null) {
                        return stoppedAfterFailure;
                    }
                    if (failure.type() == LlmErrorType.EMPTY_RESPONSE && ++emptyResponses < 2) {
                        continue;
                    }
                    String reason = failure.type() == LlmErrorType.EMPTY_RESPONSE ? "EMPTY_RESPONSE" : failure.type().name();
                    return finish(lifecycle, sequencer, runId, RunStatus.FAILED, reason, "", iterations, usage);
                }
                RunResult stoppedAfterModel = finishIfStopped(lifecycle, sequencer, runId, stopReason, iterations, usage);
                if (stoppedAfterModel != null) {
                    return stoppedAfterModel;
                }
                usage = receivedUsage ? combineUsage(usage, response.usage()) : response.usage();
                receivedUsage = true;
                sequencer.emit(new RunEvent.ModelRequestCompleted(
                        sequencer.metadata(iterations), response.usage()));
                if (!response.hasToolCalls()) {
                    if (response.content().isBlank()) {
                        if (++emptyResponses < 2) {
                            continue;
                        }
                        return finish(lifecycle, sequencer, runId, RunStatus.FAILED,
                                "EMPTY_RESPONSE", "", iterations, usage);
                    }
                    workingHistory.add(ChatMessage.assistant(response.content()));
                    committedHistory = List.copyOf(workingHistory);
                    return finish(lifecycle, sequencer, runId, RunStatus.COMPLETED,
                            "", response.content(), iterations, usage);
                }
                emptyResponses = 0;
                String protocolFailure = validateCalls(response.toolCalls(), callIds);
                if (protocolFailure != null) {
                    return finish(lifecycle, sequencer, runId, RunStatus.FAILED,
                            protocolFailure, "", iterations, usage);
                }
                workingHistory.add(ChatMessage.assistant(response.content(), response.toolCalls()));
                lifecycle.transitionTo(RunStatus.CALLING_TOOL);
                List<ToolResult> results = new ArrayList<>();
                for (ToolCall call : response.toolCalls()) {
                    RunResult stoppedBeforeTool = finishIfStopped(
                            lifecycle, sequencer, runId, stopReason, iterations, usage);
                    if (stoppedBeforeTool != null) {
                        return stoppedBeforeTool;
                    }
                    lifecycle.requireActive();
                    sequencer.emit(new RunEvent.ToolStarted(sequencer.metadata(iterations),
                            call.name(), summarize(call.argumentsJson())));
                    ToolResult result = executor.execute(call, operationCancellation);
                    RunResult stoppedAfterTool = finishIfStopped(
                            lifecycle, sequencer, runId, stopReason, iterations, usage);
                    if (stoppedAfterTool != null) {
                        return stoppedAfterTool;
                    }
                    if (result == null) {
                        return finish(lifecycle, sequencer, runId, RunStatus.FAILED,
                                "INTERNAL_ERROR", "", iterations, usage);
                    }
                    results.add(result);
                    sequencer.emit(new RunEvent.ToolCompleted(sequencer.metadata(iterations),
                            result.toolName(), result.status(), result.elapsedMillis(), result.summary()));
                    workingHistory.add(ChatMessage.tool(call.id(), result.observationJson(mapper)));
                }
                lifecycle.transitionTo(RunStatus.OBSERVING);
                iterations++;
                sequencer.emit(new RunEvent.IterationCompleted(
                        sequencer.metadata(iterations), response.toolCalls().size()));
                if (recordsRepetition(repetitionGuard, response.toolCalls(), results)) {
                    return finish(lifecycle, sequencer, runId, RunStatus.LIMIT_REACHED,
                            "REPETITION", "", iterations, usage);
                }
            }
        } catch (RuntimeException failure) {
            return finish(lifecycle, sequencer, runId, RunStatus.FAILED, "INTERNAL_ERROR", "", iterations, usage);
        }
    }

    @Override
    public synchronized void clearHistory() {
        committedHistory = List.of(systemMessage);
    }

    @Override
    public synchronized List<ChatMessage> history() {
        return committedHistory;
    }

    private static void requestStop(
            AtomicReference<StopReason> stopReason, StopReason requested, CancellationToken operationCancellation) {
        if (stopReason.compareAndSet(StopReason.NONE, requested)) {
            operationCancellation.cancel();
        }
    }

    private RunResult finishIfStopped(
            RunLifecycle lifecycle,
            EventSequencer sequencer,
            String runId,
            AtomicReference<StopReason> stopReason,
            int iterations,
            TokenUsage usage) {
        StopReason reason = stopReason.get();
        return reason == StopReason.NONE ? null : finishForStop(lifecycle, sequencer, runId, reason, iterations, usage);
    }

    private RunResult finishForStop(
            RunLifecycle lifecycle, EventSequencer sequencer, String runId, StopReason reason, int iterations, TokenUsage usage) {
        return switch (reason) {
            case USER -> finish(lifecycle, sequencer, runId, RunStatus.CANCELED,
                    "USER_CANCELED", "", iterations, usage);
            case TIMEOUT -> finish(lifecycle, sequencer, runId, RunStatus.FAILED,
                    "TIMEOUT", "", iterations, usage);
            case NONE -> throw new IllegalArgumentException("A stop reason is required");
        };
    }

    private RunResult finish(
            RunLifecycle lifecycle,
            EventSequencer sequencer,
            String runId,
            RunStatus status,
            String reason,
            String finalAnswer,
            int iterations,
            TokenUsage usage) {
        if (lifecycle.finish(status)) {
            RunEvent.Metadata metadata = sequencer.metadata(iterations);
            switch (status) {
                case COMPLETED -> sequencer.emit(new RunEvent.RunCompleted(metadata, finalAnswer, usage));
                case FAILED -> sequencer.emit(new RunEvent.RunFailed(metadata, reason));
                case CANCELED -> sequencer.emit(new RunEvent.RunCancelled(metadata, reason));
                case LIMIT_REACHED -> sequencer.emit(new RunEvent.RunLimitReached(metadata, reason));
                default -> throw new IllegalArgumentException("Terminal status is required");
            }
        }
        return new RunResult(runId, status, finalAnswer, reason, iterations, usage);
    }

    private static String validateCalls(List<ToolCall> calls, Set<String> callIds) {
        for (ToolCall call : calls) {
            if (call == null || call.id() == null || call.id().isBlank()) {
                return "INVALID_TOOL_CALL_ID";
            }
            if (call.name() == null || call.name().isBlank()) {
                return "INVALID_TOOL_NAME";
            }
            if (!callIds.add(call.id())) {
                return "DUPLICATE_TOOL_CALL_ID";
            }
        }
        return null;
    }

    private static boolean recordsRepetition(
            RepetitionGuard repetitionGuard, List<ToolCall> calls, List<ToolResult> results) {
        try {
            return repetitionGuard.recordCompletedIteration(calls, results);
        } catch (IllegalArgumentException malformedArguments) {
            // Invalid arguments are a recoverable tool observation, not an agent protocol failure.
            return false;
        }
    }

    private static String requireRunId(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runIdSupplier must return a non-blank value");
        }
        return runId;
    }

    private static TokenUsage combineUsage(TokenUsage previous, TokenUsage next) {
        if (!previous.known() || !next.known()) {
            return TokenUsage.unknown();
        }
        return new TokenUsage(previous.inputTokens() + next.inputTokens(), previous.outputTokens() + next.outputTokens(), true);
    }

    private static String summarize(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 200 ? value : value.substring(0, 200) + "…";
    }

    private enum StopReason {
        NONE,
        USER,
        TIMEOUT
    }

    private static final class EventSequencer {
        private final String runId;
        private final RunEventSink sink;
        private final Clock clock;
        private long sequence;

        private EventSequencer(String runId, RunEventSink sink, Clock clock) {
            this.runId = runId;
            this.sink = sink;
            this.clock = clock;
        }

        private RunEvent.Metadata metadata(int iteration) {
            return new RunEvent.Metadata(runId, ++sequence, Instant.now(clock), iteration);
        }

        private void emit(RunEvent event) {
            sink.accept(event);
        }
    }
}
