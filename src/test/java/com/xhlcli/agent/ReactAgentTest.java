package com.xhlcli.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.llm.LlmClient;
import com.xhlcli.llm.StreamListener;
import com.xhlcli.model.ChatMessage;
import com.xhlcli.model.ChatResponse;
import com.xhlcli.model.RunEvent;
import com.xhlcli.model.RunResult;
import com.xhlcli.model.RunStatus;
import com.xhlcli.model.TokenUsage;
import com.xhlcli.model.ToolCall;
import com.xhlcli.model.ToolDefinition;
import com.xhlcli.model.ToolMetadata;
import com.xhlcli.model.ToolResult;
import com.xhlcli.model.ToolResultStatus;
import com.xhlcli.tool.ToolExecutor;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactAgentTest {
    private static final ChatMessage SYSTEM = ChatMessage.system("You are XhlCLI. Use supplied tools when needed.");
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void completesAThreeStepToolProtocolAndCommitsOnlyTheCompleteHistory() throws Exception {
        RecordingClient client = new RecordingClient(List.of(
                response("", new ToolCall("time_1", "current_time", "{}")),
                response("", new ToolCall("echo_1", "echo_text", "{\"text\":\"2026-08-27T00:00:00Z\"}")),
                response("done")));
        RecordingExecutor executor = new RecordingExecutor(List.of(
                result("time_1", "current_time", ToolResultStatus.SUCCESS, "time", "{\"time\":\"2026-08-27T00:00:00Z\"}"),
                result("echo_1", "echo_text", ToolResultStatus.SUCCESS, "echo", "{\"text\":\"2026-08-27T00:00:00Z\"}")));
        ReactAgent agent = agent(client, executor, new RunLimits(5, java.time.Duration.ofMinutes(1)));
        List<RunEvent> events = new ArrayList<>();

        RunResult run = agent.run("what time is it?", events::add, new CancellationToken());

        assertEquals(RunStatus.COMPLETED, run.status());
        assertEquals("done", run.finalAnswer());
        assertEquals(2, run.iterations());
        assertEquals(3, client.requests.size());
        assertEquals(List.of(SYSTEM, ChatMessage.user("what time is it?")), client.requests.get(0));
        assertEquals(ChatMessage.assistant("", List.of(new ToolCall("time_1", "current_time", "{}"))),
                client.requests.get(1).get(2));
        assertEquals("time_1", client.requests.get(1).get(3).toolCallId());
        assertEquals(ChatMessage.assistant("", List.of(new ToolCall(
                "echo_1", "echo_text", "{\"text\":\"2026-08-27T00:00:00Z\"}"))), client.requests.get(2).get(4));
        assertEquals("echo_1", client.requests.get(2).get(5).toolCallId());
        assertEquals(List.of("current_time", "echo_text"), executor.executedNames());
        assertEquals(7, agent.history().size());
        assertEquals(ChatMessage.assistant("done"), agent.history().get(6));
        assertEquals(1, events.stream().filter(ReactAgentTest::isTerminal).count());
    }

    @Test
    void returnsRecoverableToolObservationsToTheModelUntilItCorrectsTheCall() throws Exception {
        RecordingClient client = new RecordingClient(List.of(
                response("", new ToolCall("unknown", "missing_tool", "{}")),
                response("", new ToolCall("invalid", "echo_text", "{bad-json}")),
                response("", new ToolCall("throws", "throwing_tool", "{}")),
                response("", new ToolCall("correct", "echo_text", "{\"text\":\"ok\"}")),
                response("fixed")));
        RecordingExecutor executor = new RecordingExecutor(List.of(
                result("unknown", "missing_tool", ToolResultStatus.UNKNOWN_TOOL, "unknown", "{}"),
                result("invalid", "echo_text", ToolResultStatus.VALIDATION_ERROR, "invalid", "{}"),
                result("throws", "throwing_tool", ToolResultStatus.EXECUTION_ERROR, "failed", "{}"),
                result("correct", "echo_text", ToolResultStatus.SUCCESS, "ok", "{\"text\":\"ok\"}")));
        ReactAgent agent = agent(client, executor, new RunLimits(5, java.time.Duration.ofMinutes(1)));

        RunResult run = agent.run("recover", ignored -> {}, new CancellationToken());

        assertEquals(RunStatus.COMPLETED, run.status());
        assertEquals(5, client.requests.size());
        assertEquals(List.of(ToolResultStatus.UNKNOWN_TOOL, ToolResultStatus.VALIDATION_ERROR,
                ToolResultStatus.EXECUTION_ERROR, ToolResultStatus.SUCCESS), executor.statuses());
        assertTrue(client.requests.get(1).get(3).content().contains("unknown_tool"));
        assertTrue(client.requests.get(2).get(5).content().contains("validation_error"));
        assertTrue(client.requests.get(3).get(7).content().contains("execution_error"));
    }

    @Test
    void executesABatchInProviderOrderAndDoesNotTreatAccompanyingTextAsAFinalAnswer() throws Exception {
        RecordingClient client = new RecordingClient(List.of(
                response("I will check both.",
                        new ToolCall("first", "first_tool", "{}"),
                        new ToolCall("second", "second_tool", "{}")),
                response("complete")));
        OrderedExecutor executor = new OrderedExecutor(List.of(
                result("first", "first_tool", ToolResultStatus.SUCCESS, "first", "{}"),
                result("second", "second_tool", ToolResultStatus.SUCCESS, "second", "{}")));
        ReactAgent agent = agent(client, executor, new RunLimits(5, java.time.Duration.ofMinutes(1)));

        RunResult run = agent.run("batch", ignored -> {}, new CancellationToken());

        assertEquals(RunStatus.COMPLETED, run.status());
        assertEquals("complete", run.finalAnswer());
        assertEquals(2, client.requests.size());
        assertEquals(List.of("first_tool", "second_tool"), executor.executedNames());
        assertFalse(executor.secondStartedBeforeFirstCompleted);
        assertEquals("I will check both.", agent.history().get(2).content());
    }

    @Test
    void reachesTheIterationLimitBeforeMakingAnotherModelRequest() throws Exception {
        RecordingClient client = new RecordingClient(List.of(
                response("", new ToolCall("one", "echo_text", "{\"text\":\"one\"}")),
                response("never requested")));
        RecordingExecutor executor = new RecordingExecutor(List.of(
                result("one", "echo_text", ToolResultStatus.SUCCESS, "one", "{\"text\":\"one\"}")));
        ReactAgent agent = agent(client, executor, new RunLimits(1, java.time.Duration.ofMinutes(1)));

        RunResult run = agent.run("limit", ignored -> {}, new CancellationToken());

        assertEquals(RunStatus.LIMIT_REACHED, run.status());
        assertEquals("MAX_ITERATIONS", run.reason());
        assertEquals(1, client.requests.size());
        assertEquals(List.of(SYSTEM), agent.history());
    }

    @Test
    void stopsAfterThreeEquivalentCompletedToolIterations() throws Exception {
        RecordingClient client = new RecordingClient(List.of(
                response("", new ToolCall("one", "echo_text", "{\"b\":2,\"a\":1}")),
                response("", new ToolCall("two", "echo_text", "{\"a\":1,\"b\":2}")),
                response("", new ToolCall("three", "echo_text", "{\"b\":2,\"a\":1}")),
                response("never requested")));
        RecordingExecutor executor = new RecordingExecutor(List.of(
                result("one", "echo_text", ToolResultStatus.SUCCESS, "same", "{\"result\":true}"),
                result("two", "echo_text", ToolResultStatus.SUCCESS, "same", "{\"result\":true}"),
                result("three", "echo_text", ToolResultStatus.SUCCESS, "same", "{\"result\":true}")));
        ReactAgent agent = agent(client, executor, new RunLimits(5, java.time.Duration.ofMinutes(1)));

        RunResult run = agent.run("repeat", ignored -> {}, new CancellationToken());

        assertEquals(RunStatus.LIMIT_REACHED, run.status());
        assertEquals("REPETITION", run.reason());
        assertEquals(3, run.iterations());
        assertEquals(3, client.requests.size());
    }

    @Test
    void mapsUserCancellationDuringAModelRequestToOneCancelledTerminalEvent() throws Exception {
        BlockingModel client = new BlockingModel();
        ReactAgent agent = agent(client, new RecordingExecutor(List.of()), new RunLimits(5, java.time.Duration.ofMinutes(1)),
                new NoopTimeoutScheduler(), List.of());
        CancellationToken userCancellation = new CancellationToken();
        List<RunEvent> events = new ArrayList<>();

        RunResult run = inWorker(() -> agent.run("cancel", events::add, userCancellation), client.started, userCancellation::cancel);

        assertEquals(RunStatus.CANCELED, run.status());
        assertEquals("USER_CANCELED", run.reason());
        assertTerminalEventInvariants(events);
        assertEquals(1, client.requests);
    }

    @Test
    void mapsATimeoutDuringAToolToFailureWithoutStartingLaterWork() throws Exception {
        RecordingClient client = new RecordingClient(List.of(
                response("", new ToolCall("slow", "echo_text", "{\"text\":\"slow\"}")),
                response("never requested")));
        BlockingTool executor = new BlockingTool(result("slow", "echo_text", ToolResultStatus.CANCELLED, "cancelled", "{}"));
        ManualTimeoutScheduler scheduler = new ManualTimeoutScheduler();
        ReactAgent agent = agent(client, executor, new RunLimits(5, java.time.Duration.ofMinutes(1)), scheduler,
                List.of(definition("echo_text")));
        List<RunEvent> events = new ArrayList<>();

        RunResult run = inWorker(() -> agent.run("timeout", events::add, new CancellationToken()), executor.started, scheduler::fire);

        assertEquals(RunStatus.FAILED, run.status());
        assertEquals("TIMEOUT", run.reason());
        assertEquals(1, client.requests.size());
        assertEquals(1, executor.executedNames().size());
        assertTerminalEventInvariants(events);
    }

    @Test
    void rejectsDuplicateCallIdsWithoutExecutingAnAmbiguousBatchOrCommittingIt() {
        RecordingClient client = new RecordingClient(List.of(response("",
                new ToolCall("dup", "echo_text", "{}"), new ToolCall("dup", "echo_text", "{}"))));
        RecordingExecutor executor = new RecordingExecutor(List.of());
        ReactAgent agent = agent(client, executor, new RunLimits(5, java.time.Duration.ofMinutes(1)));

        RunResult run = agent.run("duplicates", ignored -> {}, new CancellationToken());

        assertEquals(RunStatus.FAILED, run.status());
        assertEquals("DUPLICATE_TOOL_CALL_ID", run.reason());
        assertTrue(executor.executedNames().isEmpty());
        assertEquals(List.of(SYSTEM), agent.history());
    }

    @Test
    void rejectsAReusedCallIdInALaterIterationBeforeExecutingIt() throws Exception {
        RecordingClient client = new RecordingClient(List.of(
                response("", new ToolCall("once", "echo_text", "{}")),
                response("", new ToolCall("once", "echo_text", "{}"))));
        RecordingExecutor executor = new RecordingExecutor(List.of(
                result("once", "echo_text", ToolResultStatus.SUCCESS, "once", "{}")));
        ReactAgent agent = agent(client, executor, new RunLimits(5, java.time.Duration.ofMinutes(1)));

        RunResult run = agent.run("reuse", ignored -> {}, new CancellationToken());

        assertEquals(RunStatus.FAILED, run.status());
        assertEquals("DUPLICATE_TOOL_CALL_ID", run.reason());
        assertEquals(List.of("echo_text"), executor.executedNames());
    }

    @Test
    void retriesOneEmptyResponseThenFailsTheSecondWithoutCommittingHistory() {
        EmptyResponseClient client = new EmptyResponseClient();
        ReactAgent agent = agent(client, new RecordingExecutor(List.of()), new RunLimits(5, java.time.Duration.ofMinutes(1)),
                new NoopTimeoutScheduler(), List.of());
        List<RunEvent> events = new ArrayList<>();

        RunResult run = agent.run("empty", events::add, new CancellationToken());

        assertEquals(RunStatus.FAILED, run.status());
        assertEquals("EMPTY_RESPONSE", run.reason());
        assertEquals(2, client.requests);
        assertEquals(List.of(SYSTEM), agent.history());
        assertTerminalEventInvariants(events);
    }

    @Test
    void allowsNormalTextCompletionWhenNoToolsAreRegistered() {
        RecordingClient client = new RecordingClient(List.of(response("text only")));
        ReactAgent agent = agent(client, new RecordingExecutor(List.of()), new RunLimits(5, java.time.Duration.ofMinutes(1)),
                new NoopTimeoutScheduler(), List.of());

        RunResult run = agent.run("no tools", ignored -> {}, new CancellationToken());

        assertEquals(RunStatus.COMPLETED, run.status());
        assertTrue(client.requestedTools.get(0).isEmpty());
    }

    @Test
    void accumulatesKnownUsageAcrossToolAndFinalModelRequests() throws Exception {
        RecordingClient client = new RecordingClient(List.of(
                response("", new TokenUsage(2, 3, true), new ToolCall("one", "echo_text", "{}")),
                response("done", new TokenUsage(5, 7, true))));
        RecordingExecutor executor = new RecordingExecutor(List.of(
                result("one", "echo_text", ToolResultStatus.SUCCESS, "one", "{}")));
        ReactAgent agent = agent(client, executor, new RunLimits(5, java.time.Duration.ofMinutes(1)));

        RunResult run = agent.run("usage", ignored -> {}, new CancellationToken());

        assertEquals(new TokenUsage(7, 10, true), run.usage());
    }

    @Test
    void doesNotStartAModelRequestWhenUserCancellationWinsTheStartGate() throws Exception {
        RecordingClient client = new RecordingClient(List.of(response("never requested")));
        WindowHook hook = new WindowHook(ReactAgent.GatePoint.MODEL_START);
        ReactAgent agent = agent(client, new RecordingExecutor(List.of()), new RunLimits(5, java.time.Duration.ofMinutes(1)),
                new NoopTimeoutScheduler(), List.of(), hook);
        CancellationToken userCancellation = new CancellationToken();
        List<RunEvent> events = new ArrayList<>();

        RunResult run = inWindow(() -> agent.run("model race", events::add, userCancellation), hook, userCancellation::cancel);

        assertEquals(RunStatus.CANCELED, run.status());
        assertEquals(0, client.requests.size());
        assertFalse(events.stream().anyMatch(RunEvent.ModelRequestStarted.class::isInstance));
        assertTerminalEventInvariants(events);
    }

    @Test
    void doesNotStartAToolWhenTimeoutWinsTheStartGate() throws Exception {
        RecordingClient client = new RecordingClient(List.of(
                response("", new ToolCall("slow", "echo_text", "{}"))));
        RecordingExecutor executor = new RecordingExecutor(List.of());
        ManualTimeoutScheduler scheduler = new ManualTimeoutScheduler();
        WindowHook hook = new WindowHook(ReactAgent.GatePoint.TOOL_START);
        ReactAgent agent = agent(client, executor, new RunLimits(5, java.time.Duration.ofMinutes(1)), scheduler,
                List.of(definition("echo_text")), hook);
        List<RunEvent> events = new ArrayList<>();

        RunResult run = inWindow(() -> agent.run("tool race", events::add, new CancellationToken()), hook, scheduler::fire);

        assertEquals(RunStatus.FAILED, run.status());
        assertEquals("TIMEOUT", run.reason());
        assertTrue(executor.executedNames().isEmpty());
        assertFalse(events.stream().anyMatch(RunEvent.ToolStarted.class::isInstance));
        assertTerminalEventInvariants(events);
    }

    @Test
    void doesNotCommitFinalHistoryWhenUserCancellationWinsTheCompletionGate() throws Exception {
        RecordingClient client = new RecordingClient(List.of(response("done")));
        WindowHook hook = new WindowHook(ReactAgent.GatePoint.COMPLETION);
        ReactAgent agent = agent(client, new RecordingExecutor(List.of()), new RunLimits(5, java.time.Duration.ofMinutes(1)),
                new NoopTimeoutScheduler(), List.of(), hook);
        CancellationToken userCancellation = new CancellationToken();
        List<RunEvent> events = new ArrayList<>();

        RunResult run = inWindow(() -> agent.run("completion race", events::add, userCancellation), hook,
                userCancellation::cancel);

        assertEquals(RunStatus.CANCELED, run.status());
        assertEquals(List.of(SYSTEM), agent.history());
        assertFalse(events.stream().anyMatch(RunEvent.RunCompleted.class::isInstance));
        assertTerminalEventInvariants(events);
    }

    @Test
    void containsEventSinkFailuresAndAttemptsOnlyOneTerminalPublication() {
        RecordingClient client = new RecordingClient(List.of(response("done")));
        ReactAgent agent = agent(client, new RecordingExecutor(List.of()), new RunLimits(5, java.time.Duration.ofMinutes(1)),
                new NoopTimeoutScheduler(), List.of());
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();

        RunResult run = agent.run("broken sink", event -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("sink is unavailable");
        }, new CancellationToken());

        assertEquals(RunStatus.FAILED, run.status());
        assertEquals("INTERNAL_ERROR", run.reason());
        assertEquals(2, attempts.get());
        assertEquals(List.of(SYSTEM), agent.history());
    }

    @Test
    void containsRunIdSupplierFailuresAndUsesDistinctFallbackRunIds() {
        List<RunEvent> throwingEvents = new ArrayList<>();
        List<RunEvent> blankEvents = new ArrayList<>();
        ReactAgent throwing = agentWithRunIdSupplier(() -> {
            throw new IllegalStateException("id source failed");
        });
        ReactAgent blank = agentWithRunIdSupplier(() -> " ");

        RunResult throwingRun = throwing.run("id", throwingEvents::add, new CancellationToken());
        RunResult blankRun = blank.run("id", blankEvents::add, new CancellationToken());

        assertEquals(RunStatus.FAILED, throwingRun.status());
        assertEquals("INTERNAL_ERROR", throwingRun.reason());
        assertEquals(RunStatus.FAILED, blankRun.status());
        assertEquals("INTERNAL_ERROR", blankRun.reason());
        assertEquals(1, throwingEvents.stream().filter(ReactAgentTest::isTerminal).count());
        assertEquals(1, blankEvents.stream().filter(ReactAgentTest::isTerminal).count());
        assertFalse(throwingRun.runId().isBlank());
        assertFalse(blankRun.runId().isBlank());
        assertFalse(throwingRun.runId().equals(blankRun.runId()));
        assertEquals(0, throwingEvents.stream().filter(RunEvent.RunStarted.class::isInstance).count());
        assertEquals(0, blankEvents.stream().filter(RunEvent.RunStarted.class::isInstance).count());
    }

    private ReactAgent agent(RecordingClient client, ToolExecutor executor, RunLimits limits) {
        return agent(client, executor, limits, new NoopTimeoutScheduler(), List.of(definition("current_time"),
                definition("echo_text"), definition("first_tool"), definition("second_tool"), definition("throwing_tool")));
    }

    private ReactAgent agent(
            LlmClient client, ToolExecutor executor, RunLimits limits, TimeoutScheduler scheduler,
            List<ToolDefinition> definitions) {
        return agent(client, executor, limits, scheduler, definitions, point -> {});
    }

    private ReactAgent agent(
            LlmClient client, ToolExecutor executor, RunLimits limits, TimeoutScheduler scheduler,
            List<ToolDefinition> definitions, ReactAgent.GateHook hook) {
        return new ReactAgent(
                SYSTEM,
                client,
                executor,
                definitions,
                limits,
                scheduler,
                mapper,
                Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC),
                () -> "run-1",
                hook);
    }

    private ReactAgent agentWithRunIdSupplier(java.util.function.Supplier<String> runIdSupplier) {
        return new ReactAgent(
                SYSTEM,
                new RecordingClient(List.of(response("never requested"))),
                new RecordingExecutor(List.of()),
                List.of(),
                new RunLimits(5, java.time.Duration.ofMinutes(1)),
                new NoopTimeoutScheduler(),
                mapper,
                Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC),
                runIdSupplier);
    }

    private ToolDefinition definition(String name) {
        return new ToolDefinition(name, name, mapper.createObjectNode().put("type", "object"), ToolMetadata.conservative());
    }

    private ChatResponse response(String text, ToolCall... calls) {
        return response(text, TokenUsage.unknown(), calls);
    }

    private ChatResponse response(String text, TokenUsage usage, ToolCall... calls) {
        return new ChatResponse(text, List.of(calls), usage);
    }

    private ToolResult result(String id, String name, ToolResultStatus status, String summary, String data) throws Exception {
        return new ToolResult(id, name, status, summary, mapper.readTree(data), 0, false, data.length(), "");
    }

    private static boolean isTerminal(RunEvent event) {
        return event instanceof RunEvent.RunCompleted || event instanceof RunEvent.RunFailed
                || event instanceof RunEvent.RunCancelled || event instanceof RunEvent.RunLimitReached;
    }

    private static void assertTerminalEventInvariants(List<RunEvent> events) {
        assertEquals(1, events.stream().filter(ReactAgentTest::isTerminal).count());
        long previousSequence = 0;
        boolean terminalSeen = false;
        for (RunEvent event : events) {
            assertTrue(event.metadata().sequence() > previousSequence);
            assertFalse(terminalSeen);
            previousSequence = event.metadata().sequence();
            terminalSeen = isTerminal(event);
        }
    }

    private static RunResult inWorker(
            java.util.concurrent.Callable<RunResult> task, CountDownLatch started, Runnable stop) throws Exception {
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            Future<RunResult> result = workers.submit(task);
            assertTrue(started.await(1, TimeUnit.SECONDS));
            stop.run();
            return result.get(1, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }
    }

    private static RunResult inWindow(
            java.util.concurrent.Callable<RunResult> task, WindowHook hook, Runnable stop) throws Exception {
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            Future<RunResult> result = workers.submit(task);
            assertTrue(hook.entered.await(1, TimeUnit.SECONDS));
            stop.run();
            hook.release.countDown();
            return result.get(1, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }
    }

    private static final class RecordingClient implements LlmClient {
        private final Deque<ChatResponse> responses;
        private final List<List<ChatMessage>> requests = new ArrayList<>();
        private final List<List<ToolDefinition>> requestedTools = new ArrayList<>();

        private RecordingClient(List<ChatResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse stream(List<ChatMessage> messages, List<ToolDefinition> tools,
                StreamListener listener, CancellationToken cancellationToken) {
            requests.add(List.copyOf(messages));
            requestedTools.add(List.copyOf(tools));
            ChatResponse response = responses.removeFirst();
            if (!response.content().isEmpty()) {
                listener.onTextDelta(response.content());
            }
            return response;
        }
    }

    private static class RecordingExecutor implements ToolExecutor {
        private final Deque<ToolResult> results;
        private final List<ToolResultStatus> statuses = new ArrayList<>();
        private final List<String> names = new ArrayList<>();

        private RecordingExecutor(List<ToolResult> results) {
            this.results = new ArrayDeque<>(results);
        }

        @Override
        public ToolResult execute(ToolCall call, CancellationToken cancellationToken) {
            names.add(call.name());
            ToolResult result = results.removeFirst();
            statuses.add(result.status());
            return result;
        }

        List<String> executedNames() {
            return List.copyOf(names);
        }

        List<ToolResultStatus> statuses() {
            return List.copyOf(statuses);
        }
    }

    private static final class OrderedExecutor extends RecordingExecutor {
        private boolean firstCompleted;
        private boolean secondStartedBeforeFirstCompleted;

        private OrderedExecutor(List<ToolResult> results) {
            super(results);
        }

        @Override
        public ToolResult execute(ToolCall call, CancellationToken cancellationToken) {
            if ("second_tool".equals(call.name()) && !firstCompleted) {
                secondStartedBeforeFirstCompleted = true;
            }
            ToolResult result = super.execute(call, cancellationToken);
            if ("first_tool".equals(call.name())) {
                firstCompleted = true;
            }
            return result;
        }
    }

    private static final class NoopTimeoutScheduler implements TimeoutScheduler {
        @Override
        public Registration schedule(java.time.Duration delay, Runnable action) {
            return () -> {};
        }

        @Override
        public void close() {}
    }

    private static final class EmptyResponseClient implements LlmClient {
        private int requests;

        @Override
        public ChatResponse stream(List<ChatMessage> messages, List<ToolDefinition> tools,
                StreamListener listener, CancellationToken cancellationToken) throws com.xhlcli.llm.LlmException {
            requests++;
            throw new com.xhlcli.llm.LlmException(
                    com.xhlcli.llm.LlmErrorType.EMPTY_RESPONSE, "empty", false, false);
        }
    }

    private static final class BlockingModel implements LlmClient {
        private final CountDownLatch started = new CountDownLatch(1);
        private int requests;

        @Override
        public ChatResponse stream(List<ChatMessage> messages, List<ToolDefinition> tools,
                StreamListener listener, CancellationToken cancellationToken) throws com.xhlcli.llm.LlmException {
            requests++;
            CountDownLatch cancelled = new CountDownLatch(1);
            try (CancellationToken.Registration ignored = cancellationToken.onCancel(cancelled::countDown)) {
                started.countDown();
                if (!cancelled.await(1, TimeUnit.SECONDS)) {
                    throw new AssertionError("test did not cancel the model request");
                }
                throw new com.xhlcli.llm.LlmException(
                        com.xhlcli.llm.LlmErrorType.CANCELLED, "cancelled", false, false);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new com.xhlcli.llm.LlmException(
                        com.xhlcli.llm.LlmErrorType.CANCELLED, "interrupted", false, false, failure);
            }
        }
    }

    private static final class BlockingTool extends RecordingExecutor {
        private final CountDownLatch started = new CountDownLatch(1);

        private BlockingTool(ToolResult result) {
            super(List.of(result));
        }

        @Override
        public ToolResult execute(ToolCall call, CancellationToken cancellationToken) {
            CountDownLatch cancelled = new CountDownLatch(1);
            try (CancellationToken.Registration ignored = cancellationToken.onCancel(cancelled::countDown)) {
                started.countDown();
                if (!cancelled.await(1, TimeUnit.SECONDS)) {
                    throw new AssertionError("test did not time out the tool");
                }
                return super.execute(call, cancellationToken);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
        }
    }

    private static final class ManualTimeoutScheduler implements TimeoutScheduler {
        private Runnable action;

        @Override
        public Registration schedule(java.time.Duration delay, Runnable action) {
            this.action = action;
            return () -> this.action = null;
        }

        void fire() {
            action.run();
        }

        @Override
        public void close() {}
    }

    private static final class WindowHook implements ReactAgent.GateHook {
        private final ReactAgent.GatePoint point;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private WindowHook(ReactAgent.GatePoint point) {
            this.point = point;
        }

        @Override
        public void beforeGatePoint(ReactAgent.GatePoint candidate) {
            if (candidate != point) {
                return;
            }
            entered.countDown();
            try {
                if (!release.await(1, TimeUnit.SECONDS)) {
                    throw new AssertionError("test did not release gate hook");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
        }
    }
}
