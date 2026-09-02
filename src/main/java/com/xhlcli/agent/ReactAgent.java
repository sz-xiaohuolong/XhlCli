package com.xhlcli.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.llm.LlmClient;
import com.xhlcli.llm.LlmErrorType;
import com.xhlcli.llm.LlmException;
import com.xhlcli.config.SecretRedactor;
import com.xhlcli.config.StreamingSecretRedactor;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.function.Function;

/** Drives one serial structured-tool ReAct loop without committing partial protocol history. */
public final class ReactAgent implements AgentRunner {
    private static final AtomicLong FALLBACK_RUN_SEQUENCE = new AtomicLong();
    private static final Function<String, String> DEFAULT_EVENT_SANITIZER = value -> SecretRedactor.redact(value, null);
    private static final Supplier<StreamingSecretRedactor> DEFAULT_STREAM_SANITIZER =
            () -> new StreamingSecretRedactor(null);
    private final ChatMessage systemMessage;
    private final LlmClient client;
    private final ToolExecutor executor;
    private final List<ToolDefinition> toolDefinitions;
    private final RunLimits limits;
    private final TimeoutScheduler timeoutScheduler;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Supplier<String> runIdSupplier;
    private final Function<String, String> eventSanitizer;
    private final Supplier<StreamingSecretRedactor> streamingSanitizerSupplier;
    private final GateHook gateHook;
    private List<ChatMessage> committedHistory;
    private com.xhlcli.context.ContextAssembler contextAssembler;
    private com.xhlcli.memory.ConversationHistoryCompactor compactor;
    private java.util.function.Supplier<java.util.List<com.xhlcli.memory.MemoryEntry>> memorySupplier;

    public void setContextAssembler(com.xhlcli.context.ContextAssembler contextAssembler) {
        this.contextAssembler = contextAssembler;
    }

    public void setCompactor(com.xhlcli.memory.ConversationHistoryCompactor compactor) {
        this.compactor = compactor;
    }

    public void setMemorySupplier(java.util.function.Supplier<java.util.List<com.xhlcli.memory.MemoryEntry>> memorySupplier) {
        this.memorySupplier = memorySupplier;
    }

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
        this(systemMessage, client, executor, toolDefinitions, limits, timeoutScheduler, mapper, clock,
                runIdSupplier, DEFAULT_EVENT_SANITIZER, DEFAULT_STREAM_SANITIZER, GateHook.NOOP);
    }

    public ReactAgent(
            ChatMessage systemMessage,
            LlmClient client,
            ToolExecutor executor,
            List<ToolDefinition> toolDefinitions,
            RunLimits limits,
            TimeoutScheduler timeoutScheduler,
            ObjectMapper mapper,
            Clock clock,
            Supplier<String> runIdSupplier,
            Function<String, String> eventSanitizer) {
        this(systemMessage, client, executor, toolDefinitions, limits, timeoutScheduler, mapper, clock,
                runIdSupplier, eventSanitizer,
                () -> StreamingSecretRedactor.buffered(eventSanitizer), GateHook.NOOP);
    }

    public ReactAgent(
            ChatMessage systemMessage,
            LlmClient client,
            ToolExecutor executor,
            List<ToolDefinition> toolDefinitions,
            RunLimits limits,
            TimeoutScheduler timeoutScheduler,
            ObjectMapper mapper,
            Clock clock,
            Supplier<String> runIdSupplier,
            Function<String, String> eventSanitizer,
            Supplier<StreamingSecretRedactor> streamingSanitizerSupplier) {
        this(systemMessage, client, executor, toolDefinitions, limits, timeoutScheduler, mapper, clock,
                runIdSupplier, eventSanitizer, streamingSanitizerSupplier, GateHook.NOOP);
    }

    ReactAgent(
            ChatMessage systemMessage,
            LlmClient client,
            ToolExecutor executor,
            List<ToolDefinition> toolDefinitions,
            RunLimits limits,
            TimeoutScheduler timeoutScheduler,
            ObjectMapper mapper,
            Clock clock,
            Supplier<String> runIdSupplier,
            GateHook gateHook) {
        this(systemMessage, client, executor, toolDefinitions, limits, timeoutScheduler, mapper, clock,
                runIdSupplier, DEFAULT_EVENT_SANITIZER, DEFAULT_STREAM_SANITIZER, gateHook);
    }

    ReactAgent(
            ChatMessage systemMessage,
            LlmClient client,
            ToolExecutor executor,
            List<ToolDefinition> toolDefinitions,
            RunLimits limits,
            TimeoutScheduler timeoutScheduler,
            ObjectMapper mapper,
            Clock clock,
            Supplier<String> runIdSupplier,
            Function<String, String> eventSanitizer,
            Supplier<StreamingSecretRedactor> streamingSanitizerSupplier,
            GateHook gateHook) {
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
        this.eventSanitizer = Objects.requireNonNull(eventSanitizer, "eventSanitizer");
        this.streamingSanitizerSupplier = Objects.requireNonNull(
                streamingSanitizerSupplier, "streamingSanitizerSupplier");
        this.gateHook = Objects.requireNonNull(gateHook, "gateHook");
        this.committedHistory = List.of(systemMessage);
    }

    @Override
    public synchronized RunResult run(String input, RunEventSink events, CancellationToken userCancellation) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(userCancellation, "userCancellation");
        RunIdResolution runIdResolution = resolveRunId();
        String runId = runIdResolution.runId();
        RunLifecycle lifecycle = new RunLifecycle();
        EventSequencer sequencer = new EventSequencer(
                runId, events, clock, eventSanitizer, streamingSanitizerSupplier);
        CancellationToken operationCancellation = new CancellationToken();
        StartStopGate gate = new StartStopGate(gateHook);
        List<ChatMessage> workingHistory = new ArrayList<>(committedHistory);
        TokenUsage usage = TokenUsage.unknown();
        boolean receivedUsage = false;
        int iterations = 0;

        if (runIdResolution.failed()) {
            return finish(lifecycle, sequencer, runId, RunStatus.FAILED, "INTERNAL_ERROR", "", iterations, usage);
        }

        try (CancellationToken.Registration userRegistration = userCancellation.onCancel(
                () -> requestStop(StopReason.USER, operationCancellation, gate));
             TimeoutScheduler.Registration timeoutRegistration = timeoutScheduler.schedule(limits.timeout(),
                     () -> requestStop(StopReason.TIMEOUT, operationCancellation, gate))) {
            sequencer.emit(new RunEvent.RunStarted(sequencer.metadata(0), summarize(input)));
            if (input.isBlank()) {
                return finish(lifecycle, sequencer, runId, RunStatus.FAILED, "INVALID_INPUT", "", iterations, usage);
            }
            if (gate.stopReason() != StopReason.NONE) {
                return finishForStop(lifecycle, sequencer, runId, gate.stopReason(), iterations, usage);
            }
            workingHistory.add(ChatMessage.user(input));
            RepetitionGuard repetitionGuard = new RepetitionGuard(mapper);
            Set<String> callIds = new HashSet<>();
            int emptyResponses = 0;

            while (true) {
                RunResult stopped = finishIfStopped(lifecycle, sequencer, runId, gate, iterations, usage);
                if (stopped != null) {
                    return stopped;
                }
                if (iterations >= limits.maxIterations()) {
                    return finish(lifecycle, sequencer, runId, RunStatus.LIMIT_REACHED, "MAX_ITERATIONS", "", iterations, usage);
                }
                if (lifecycle.status() != RunStatus.THINKING) {
                    lifecycle.transitionTo(RunStatus.THINKING);
                }
                int modelIteration = iterations;
                ChatResponse response;
                try {
                    gateHook.beforePermit(GatePoint.MODEL_START);
                    if (!gate.permitStart()) {
                        RunResult stoppedBeforeModel = finishIfStopped(
                                lifecycle, sequencer, runId, gate, iterations, usage);
                        return stoppedBeforeModel != null ? stoppedBeforeModel
                                : finish(lifecycle, sequencer, runId, RunStatus.FAILED,
                                "INTERNAL_ERROR", "", iterations, usage);
                    }
                    gateHook.afterPermit(GatePoint.MODEL_START);
                    lifecycle.requireActive();
                    sequencer.emit(new RunEvent.ModelRequestStarted(sequencer.metadata(modelIteration)));
                    sequencer.beginTextStream();
                    List<ChatMessage> toSend = List.copyOf(workingHistory);
                    if (contextAssembler != null) {
                        String baseSys = "";
                        List<ChatMessage> pure = new java.util.ArrayList<>();
                        for (ChatMessage m : workingHistory) {
                            if (m.role() == ChatMessage.Role.SYSTEM) baseSys += m.content() + "\n";
                            else pure.add(m);
                        }
                        if (compactor != null && !contextAssembler.getBudget().isWithinBudget(pure)) {
                            pure = compactor.compact(pure, 5);
                            workingHistory.clear();
                            workingHistory.add(ChatMessage.system(baseSys.trim()));
                            workingHistory.addAll(pure);
                        }
                        java.util.List<com.xhlcli.memory.MemoryEntry> activeMemories = memorySupplier != null ? memorySupplier.get() : null;
                        toSend = contextAssembler.assemble(baseSys.trim(), "", "", "", "", activeMemories, null, pure);
                    }
                    response = client.stream(toSend, toolDefinitions, delta -> {
                        if (!delta.isEmpty() && gate.stopReason() == StopReason.NONE) {
                            lifecycle.requireActive();
                            sequencer.emitTextDelta(modelIteration, delta);
                        }
                    }, operationCancellation);
                    sequencer.finishTextStream(modelIteration, true);
                } catch (LlmException failure) {
                    sequencer.finishTextStream(modelIteration, gate.stopReason() == StopReason.NONE);
                    RunResult stoppedAfterFailure = finishIfStopped(
                            lifecycle, sequencer, runId, gate, iterations, usage);
                    if (stoppedAfterFailure != null) {
                        return stoppedAfterFailure;
                    }
                    if (failure.type() == LlmErrorType.EMPTY_RESPONSE && ++emptyResponses < 2) {
                        continue;
                    }
                    return finish(lifecycle, sequencer, runId, RunStatus.FAILED, failure.type().name(), "", iterations,
                            usage, failure.type(), failure.getMessage(), failure.partialResponse());
                }
                RunResult stoppedAfterModel = finishIfStopped(lifecycle, sequencer, runId, gate, iterations, usage);
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
                        return finish(lifecycle, sequencer, runId, RunStatus.FAILED, "EMPTY_RESPONSE", "", iterations,
                                usage, LlmErrorType.EMPTY_RESPONSE, "Provider returned an empty response.",
                                sequencer.hasTextDelta());
                    }
                    workingHistory.add(ChatMessage.assistant(response.content()));
                    String finalText = response.content();
                    int completionIterations = iterations;
                    TokenUsage completionUsage = usage;
                    gateHook.beforePermit(GatePoint.COMPLETION);
                    if (!gate.permitCompletion()) {
                        RunResult stoppedAtCompletion = finishIfStopped(
                                lifecycle, sequencer, runId, gate, iterations, usage);
                        return stoppedAtCompletion != null ? stoppedAtCompletion
                                : finish(lifecycle, sequencer, runId, RunStatus.FAILED,
                                "INTERNAL_ERROR", "", iterations, usage);
                    }
                    gateHook.afterPermit(GatePoint.COMPLETION);
                    committedHistory = List.copyOf(workingHistory);
                    return finish(lifecycle, sequencer, runId, RunStatus.COMPLETED,
                            "", finalText, completionIterations, completionUsage);
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
                            lifecycle, sequencer, runId, gate, iterations, usage);
                    if (stoppedBeforeTool != null) {
                        return stoppedBeforeTool;
                    }
                    int toolIteration = iterations;
                    gateHook.beforePermit(GatePoint.TOOL_START);
                    if (!gate.permitStart()) {
                        RunResult stoppedAtToolStart = finishIfStopped(
                                lifecycle, sequencer, runId, gate, iterations, usage);
                        return stoppedAtToolStart != null ? stoppedAtToolStart
                                : finish(lifecycle, sequencer, runId, RunStatus.FAILED,
                                "INTERNAL_ERROR", "", iterations, usage);
                    }
                    gateHook.afterPermit(GatePoint.TOOL_START);
                    lifecycle.requireActive();
                    sequencer.emit(new RunEvent.ToolStarted(sequencer.metadata(toolIteration),
                            call.name(), summarize(call.argumentsJson())));
                    ToolResult result = executor.execute(call, operationCancellation);
                    RunResult stoppedAfterTool = finishIfStopped(
                            lifecycle, sequencer, runId, gate, iterations, usage);
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
            StopReason requested,
            CancellationToken operationCancellation,
            StartStopGate gate) {
        if (gate.stop(requested)) {
            operationCancellation.cancel();
        }
    }

    private RunResult finishIfStopped(
            RunLifecycle lifecycle,
            EventSequencer sequencer,
            String runId,
            StartStopGate gate,
            int iterations,
            TokenUsage usage) {
        StopReason reason = gate.stopReason();
        return reason == StopReason.NONE ? null : finishForStop(lifecycle, sequencer, runId, reason, iterations, usage);
    }

    private RunResult finishForStop(
            RunLifecycle lifecycle, EventSequencer sequencer, String runId, StopReason reason, int iterations, TokenUsage usage) {
        return switch (reason) {
            case USER -> finish(lifecycle, sequencer, runId, RunStatus.CANCELED,
                    "USER_CANCELED", "", iterations, usage);
            case TIMEOUT -> finish(lifecycle, sequencer, runId, RunStatus.FAILED,
                    "TIMEOUT", "", iterations, usage, LlmErrorType.TIMEOUT,
                    "The run exceeded its overall timeout.", sequencer.hasTextDelta());
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
        return finish(lifecycle, sequencer, runId, status, reason, finalAnswer, iterations, usage, null, "", false);
    }

    private RunResult finish(
            RunLifecycle lifecycle,
            EventSequencer sequencer,
            String runId,
            RunStatus status,
            String reason,
            String finalAnswer,
            int iterations,
            TokenUsage usage,
            LlmErrorType errorType,
            String safeMessage,
            boolean partialResponse) {
        if (lifecycle.finish(status)) {
            RunEvent.Metadata metadata = sequencer.metadata(iterations);
            switch (status) {
                case COMPLETED -> sequencer.emitTerminal(new RunEvent.RunCompleted(metadata, finalAnswer, usage));
                case FAILED -> sequencer.emitTerminal(new RunEvent.RunFailed(
                        metadata, reason, errorType, safeMessage, partialResponse));
                case CANCELED -> sequencer.emitTerminal(new RunEvent.RunCancelled(metadata, reason));
                case LIMIT_REACHED -> sequencer.emitTerminal(new RunEvent.RunLimitReached(metadata, reason));
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

    private RunIdResolution resolveRunId() {
        try {
            String runId = runIdSupplier.get();
            if (runId != null && !runId.isBlank()) {
                return new RunIdResolution(sanitizeRunId(runId), false);
            }
        } catch (RuntimeException ignored) {
            // The agent still needs a valid event identity to report the initialization failure.
        }
        return new RunIdResolution(nextSafeFallbackRunId(), true);
    }

    private String sanitizeRunId(String runId) {
        try {
            String sanitized = eventSanitizer.apply(runId);
            if (sanitized != null && !sanitized.isBlank()) {
                return sanitized;
            }
        } catch (RuntimeException ignored) {
            // A safe fallback keeps the run observable without exposing the supplier value.
        }
        return nextSafeFallbackRunId();
    }

    private static String nextSafeFallbackRunId() {
        return "internal-run-" + FALLBACK_RUN_SEQUENCE.incrementAndGet();
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

    enum GatePoint {
        MODEL_START,
        TOOL_START,
        COMPLETION
    }

    interface GateHook {
        GateHook NOOP = new GateHook() {};

        default void beforePermit(GatePoint point) {}

        default void afterPermit(GatePoint point) {}
    }

    private record RunIdResolution(String runId, boolean failed) {}

    private static final class StartStopGate {
        private GateState state = GateState.ACTIVE;
        private StopReason stopReason = StopReason.NONE;

        private StartStopGate(GateHook ignoredHook) {
        }

        private synchronized boolean permitStart() {
            return state == GateState.ACTIVE;
        }

        private synchronized boolean permitCompletion() {
            if (state != GateState.ACTIVE) {
                return false;
            }
            state = GateState.COMPLETING;
            return true;
        }

        private synchronized boolean stop(StopReason requested) {
            if (state != GateState.ACTIVE) {
                return false;
            }
            state = GateState.STOPPED;
            stopReason = requested;
            return true;
        }

        private synchronized StopReason stopReason() {
            return stopReason;
        }
    }

    private enum GateState {
        ACTIVE,
        STOPPED,
        COMPLETING
    }

    private static final class EventSequencer {
        private final String runId;
        private final RunEventSink sink;
        private final Clock clock;
        private final Function<String, String> sanitizer;
        private final Supplier<StreamingSecretRedactor> streamingSanitizerSupplier;
        private StreamingSecretRedactor streamingSanitizer;
        private long sequence;
        private boolean textDeltaEmitted;

        private EventSequencer(
                String runId,
                RunEventSink sink,
                Clock clock,
                Function<String, String> sanitizer,
                Supplier<StreamingSecretRedactor> streamingSanitizerSupplier) {
            this.runId = runId;
            this.sink = sink;
            this.clock = clock;
            this.sanitizer = sanitizer;
            this.streamingSanitizerSupplier = streamingSanitizerSupplier;
        }

        private RunEvent.Metadata metadata(int iteration) {
            return new RunEvent.Metadata(runId, ++sequence, Instant.now(clock), iteration);
        }

        private void emit(RunEvent event) {
            if (event instanceof RunEvent.TextDelta) {
                textDeltaEmitted = true;
            }
            sink.accept(sanitize(event));
        }

        private void beginTextStream() {
            streamingSanitizer = Objects.requireNonNull(
                    streamingSanitizerSupplier.get(), "streaming sanitizer");
        }

        private void emitTextDelta(int iteration, String delta) {
            if (streamingSanitizer == null) {
                beginTextStream();
            }
            textDeltaEmitted = true;
            emitSafeText(iteration, streamingSanitizer.accept(delta));
        }

        private void finishTextStream(int iteration, boolean publish) {
            if (streamingSanitizer == null) {
                return;
            }
            String tail = streamingSanitizer.finish();
            streamingSanitizer = null;
            if (publish) {
                emitSafeText(iteration, tail);
            }
        }

        private void emitSafeText(int iteration, String text) {
            if (!text.isEmpty()) {
                sink.accept(new RunEvent.TextDelta(metadata(iteration), sanitize(text)));
            }
        }

        private boolean hasTextDelta() {
            return textDeltaEmitted;
        }

        private void emitTerminal(RunEvent event) {
            try {
                sink.accept(sanitize(event));
            } catch (RuntimeException ignored) {
                // A failed renderer must not make the agent throw or retry a terminal event.
            }
        }

        private RunEvent sanitize(RunEvent event) {
            RunEvent.Metadata originalMetadata = event.metadata();
            RunEvent.Metadata metadata = new RunEvent.Metadata(
                    runId,
                    originalMetadata.sequence(),
                    originalMetadata.timestamp(),
                    originalMetadata.iteration());
            return switch (event) {
                case RunEvent.RunStarted started -> new RunEvent.RunStarted(metadata, sanitize(started.inputSummary()));
                case RunEvent.ModelRequestStarted started -> started;
                case RunEvent.TextDelta delta -> new RunEvent.TextDelta(metadata, sanitize(delta.text()));
                case RunEvent.ModelRequestCompleted completed -> completed;
                case RunEvent.ToolStarted started -> new RunEvent.ToolStarted(
                        metadata, sanitize(started.toolName()), sanitize(started.argumentsSummary()));
                case RunEvent.ToolCompleted completed -> new RunEvent.ToolCompleted(
                        metadata, sanitize(completed.toolName()), completed.status(), completed.elapsedMillis(),
                        sanitize(completed.summary()));
                case RunEvent.IterationCompleted completed -> completed;
                case RunEvent.RunCompleted completed -> new RunEvent.RunCompleted(
                        metadata, sanitize(completed.finalAnswer()), completed.usage());
                case RunEvent.RunFailed failed -> new RunEvent.RunFailed(
                        metadata,
                        sanitizeRequired(failed.reason(), "REDACTED_FAILURE"),
                        failed.errorType(),
                        failed.errorType() == null ? "" : sanitizeRequired(
                                failed.safeMessage(), "Failure details were redacted."),
                        failed.partialResponse());
                case RunEvent.RunCancelled cancelled -> new RunEvent.RunCancelled(metadata, sanitize(cancelled.reason()));
                case RunEvent.RunLimitReached limit -> new RunEvent.RunLimitReached(metadata, sanitize(limit.reason()));
            };
        }

        private String sanitize(String value) {
            return Objects.requireNonNull(sanitizer.apply(value), "event sanitizer result");
        }

        private String sanitizeRequired(String value, String fallback) {
            String sanitized = sanitize(value);
            return sanitized.isBlank() ? fallback : sanitized;
        }
    }
}
