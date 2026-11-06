package com.xhlcli.cli;

import com.xhlcli.agent.AgentRunner;
import com.xhlcli.config.AgentSettings;
import com.xhlcli.config.ChatConfig;
import com.xhlcli.config.ConfigKey;
import com.xhlcli.config.ConfigSource;
import com.xhlcli.config.LogLevel;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.ChatMessage;
import com.xhlcli.model.RunEvent;
import com.xhlcli.model.RunEventSink;
import com.xhlcli.model.RunResult;
import com.xhlcli.model.RunStatus;
import com.xhlcli.model.TokenUsage;
import com.xhlcli.render.PlainRunRenderer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatLoopTest {
    @Test
    void memoryRecallPreviewExplainsSelectionWithoutStartingAgent(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        FakeAgent agent = new FakeAgent();
        Harness h = new Harness(new ListInput(List.of("/memory recall Redis", "/exit")), agent);
        var memory = new com.xhlcli.memory.MemoryManager(dir.resolve("global"), dir.resolve("project"));
        memory.saveProject("Redis 因运维复杂被放弃", "user");
        memory.saveProject("Java 21", "user");
        h.loop.setMemoryManager(memory);
        h.loop.run();
        assertTrue(agent.inputs.isEmpty());
        assertTrue(h.out().contains("Redis 因运维复杂被放弃"));
        assertTrue(h.out().contains("命中"));
        assertFalse(h.out().contains("Java 21"));
    }

    @Test
    void manualToggleStopsClassificationAndMalformedResponseFallsBackOnce() {
        FakeAgent agent = new FakeAgent();
        Harness h = new Harness(new ListInput(List.of("/team-mode auto", "第一个请求", "/team-mode manual", "第二个请求", "/exit")), agent);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        com.xhlcli.llm.LlmClient llm = (messages, tools, listener, token) -> {
            calls.incrementAndGet();
            return new com.xhlcli.model.ChatResponse("无效格式", TokenUsage.unknown());
        };
        h.loop.setLlmClient(llm);
        h.loop.setTeamOrchestrator(new com.xhlcli.team.TeamOrchestrator(llm));
        h.loop.run();
        assertEquals(List.of("第一个请求", "第二个请求"), agent.inputs);
        assertEquals(1, calls.get());
    }

    @Test
    void cancellingClassificationDoesNotLaunchEitherRunner() {
        FakeAgent agent = new FakeAgent();
        Harness h = new Harness(new ListInput(List.of("/team-mode auto", "复杂请求", "/exit")), agent);
        com.xhlcli.llm.LlmClient llm = (messages, tools, listener, token) -> {
            h.loop.cancelActiveResponse();
            return new com.xhlcli.model.ChatResponse("{\"mode\":\"TEAM\",\"reason\":\"分工\"}", TokenUsage.unknown());
        };
        var team = new com.xhlcli.team.TeamOrchestrator(llm);
        h.loop.setLlmClient(llm); h.loop.setTeamOrchestrator(team);
        h.loop.run();
        assertTrue(agent.inputs.isEmpty()); assertTrue(team.history().isEmpty());
        assertTrue(h.out().contains("已取消"));
    }

    @Test
    void teamResultReachesRealReactFollowupAndClearRemovesIt() {
        var requests = new ArrayList<List<ChatMessage>>();
        com.xhlcli.llm.LlmClient llm = (messages, tools, listener, token) -> {
            String role = messages.getFirst().content();
            String response;
            if (role.contains("Task Planning Specialist")) response = "{\"steps\":[{\"id\":\"a\",\"description\":\"分析接口\",\"type\":\"ANALYSIS\",\"dependencies\":[]}]}";
            else if (role.contains("Execution Specialist")) response = "接口结论：复用现有适配器";
            else if (role.contains("Quality Review Specialist")) response = "{\"approved\":true,\"summary\":\"通过\"}";
            else { requests.add(List.copyOf(messages)); response = "已解释"; }
            return new com.xhlcli.model.ChatResponse(response, TokenUsage.unknown());
        };
        try (var scheduler = new com.xhlcli.agent.ScheduledTimeoutScheduler()) {
            var react = new com.xhlcli.agent.ReactAgent(ChatMessage.system("项目约束：只读"), llm,
                    (call, token) -> { throw new AssertionError("此场景无需工具"); }, List.of(),
                    new com.xhlcli.agent.RunLimits(5, Duration.ofSeconds(10)), scheduler,
                    new com.fasterxml.jackson.databind.ObjectMapper(), java.time.Clock.systemUTC(), () -> "followup");
            Harness h = new Harness(new ListInput(List.of("/team 分析接口", "解释刚才的结果", "/clear", "再次提问", "/exit")), react);
            h.loop.setTeamOrchestrator(new com.xhlcli.team.TeamOrchestrator(llm, null, List.of(), null,
                    new PrintStream(new ByteArrayOutputStream())));
            h.loop.run();
            assertEquals(2, requests.size());
            assertTrue(requests.getFirst().stream().anyMatch(m -> m.content().contains("复用现有适配器")));
            assertFalse(requests.getLast().stream().anyMatch(m -> m.content().contains("复用现有适配器")));
        }
    }

    @Test
    void autoModeRoutesOrdinaryInputToExistingTeam() {
        FakeAgent agent = new FakeAgent();
        Harness h = new Harness(new ListInput(List.of("/team-mode auto", "实现跨模块功能", "/exit")), agent);
        var responses = new java.util.ArrayDeque<>(List.of(
            "{\"mode\":\"TEAM\",\"reason\":\"需要跨模块分工\"}",
            "{\"steps\":[{\"id\":\"s1\",\"description\":\"实现功能\",\"type\":\"ANALYSIS\",\"dependencies\":[]}]}",
            "已完成功能", "{\"approved\":true,\"summary\":\"通过\",\"issues\":[]}"));
        com.xhlcli.llm.LlmClient llm = (messages, tools, listener, token) ->
            new com.xhlcli.model.ChatResponse(responses.remove(), List.of(), new TokenUsage(1, 1, true));
        var team = new com.xhlcli.team.TeamOrchestrator(llm, null, List.of(), null,
            new PrintStream(new ByteArrayOutputStream()));
        h.loop.setTeamOrchestrator(team);
        h.loop.setLlmClient(llm);
        assertEquals(0, h.loop.run());
        assertTrue(agent.inputs.isEmpty(), "自动选择 Team 后不能再执行普通 Agent");
        assertEquals(2, team.history().size());
        assertTrue(h.out().contains("跨模块分工"));
    }

    @Test
    void routesCommandsBlankInputAndFiveTurnsWithoutCallingCommandsAsPrompts() {
        FakeAgent agent = new FakeAgent();
        Harness harness = new Harness(new ListInput(List.of(
                "/help", "/config", " ", "/tools", "one", "two", "three", "four", "five", "/clear", "/exit")), agent);

        assertEquals(0, harness.loop.run());

        assertEquals(List.of("one", "two", "three", "four", "five"), agent.inputs);
        assertEquals(1, agent.clearCalls);
        assertTrue(harness.out().contains("agentMaxIterations=10"));
        assertFalse(harness.out().contains("test-key"));
        assertTrue(harness.err().contains("Unknown command: /tools"));
        assertTrue(harness.out().contains("Conversation cleared."));
    }

    @Test
    void routesIndexAndSearchCommands() {
        System.setProperty("xhlcli.rag.dir", "/tmp/xhlcli-test-loop-rag");
        System.setProperty("xhlcli.embedding.provider", "fake");
        FakeAgent agent = new FakeAgent();
        Harness harness = new Harness(new ListInput(List.of(
                "/index status",
                "/search query",
                "/exit")), agent);

        assertEquals(0, harness.loop.run());
        assertTrue(harness.out().contains("索引状态") || harness.out().contains("已索引"));
        assertTrue(harness.out().contains("尚未索引") || harness.out().contains("检索") || harness.out().contains("未找到"));
    }

    @Test
    void routesPlanCommandToPlanAgent() {
        FakeAgent agent = new FakeAgent();
        Harness harness = new Harness(new ListInput(List.of(
                "/plan 查看测试代码",
                "/exit")), agent);

        com.xhlcli.plan.Planner stubPlanner = new com.xhlcli.plan.Planner(null) {
            @Override
            public com.xhlcli.plan.ExecutionPlan createPlan(String goal, CancellationToken cancellationToken) {
                com.xhlcli.plan.ExecutionPlan plan = new com.xhlcli.plan.ExecutionPlan("plan-test", goal);
                plan.addTask(new com.xhlcli.plan.Task("task_1", "只读步骤", com.xhlcli.plan.Task.TaskType.ANALYSIS));
                plan.computeExecutionOrder();
                return plan;
            }
        };
        com.xhlcli.agent.PlanExecuteAgent planAgent = new com.xhlcli.agent.PlanExecuteAgent(
                null, null, List.of(), stubPlanner, null,
                (goal, plan) -> com.xhlcli.agent.PlanExecuteAgent.PlanReviewDecision.execute(),
                new PrintStream(new ByteArrayOutputStream())
        );
        harness.loop.setPlanAgent(planAgent);

        assertEquals(0, harness.loop.run());
        assertEquals(2, planAgent.history().size());
    }

    @Test
    void routesTeamCommandToTeamOrchestrator() {
        FakeAgent agent = new FakeAgent();
        Harness harness = new Harness(new ListInput(List.of(
                "/team",
                "重构架构",
                "/exit")), agent);

        String planJson = "{\"summary\":\"测试\",\"steps\":[{\"id\":\"s1\",\"description\":\"测试步骤\",\"type\":\"CODE\",\"dependencies\":[]}]}";
        String reviewJson = "{\"approved\":true,\"summary\":\"OK\",\"issues\":[]}";

        com.xhlcli.llm.LlmClient stubLlm = new com.xhlcli.llm.LlmClient() {
            private int call = 0;
            @Override
            public com.xhlcli.model.ChatResponse stream(
                    List<ChatMessage> messages,
                    List<com.xhlcli.model.ToolDefinition> tools,
                    com.xhlcli.llm.StreamListener listener,
                    CancellationToken cancellationToken) {
                call++;
                String content = (call == 1) ? planJson : (call == 2 ? "执行成功" : reviewJson);
                if (listener != null) listener.onTextDelta(content);
                return new com.xhlcli.model.ChatResponse(content, List.of(), new TokenUsage(10, 10, true));
            }
        };

        com.xhlcli.team.TeamOrchestrator orchestrator = new com.xhlcli.team.TeamOrchestrator(
                stubLlm, null, List.of(), null, new PrintStream(new ByteArrayOutputStream()));
        harness.loop.setTeamOrchestrator(orchestrator);

        assertEquals(0, harness.loop.run());
        assertEquals(2, orchestrator.history().size());
    }

    @Test
    void eofAndIdleInterruptAreNormalInteractiveEvents() {
        Harness eof = new Harness(prompt -> { throw new InputEndOfFileException(); }, new FakeAgent());
        assertEquals(0, eof.loop.run());

        InputReader interruptedThenExit = new InputReader() {
            private boolean first = true;

            @Override
            public String readLine(String prompt) {
                if (first) {
                    first = false;
                    throw new InputInterruptedException();
                }
                return "/exit";
            }
        };
        Harness interrupted = new Harness(interruptedThenExit, new FakeAgent());
        assertFalse(interrupted.loop.cancelActiveResponse());
        assertEquals(0, interrupted.loop.run());
    }

    @Test
    void failedRunDoesNotStopTheNextTurn() {
        FailingThenSuccessfulAgent agent = new FailingThenSuccessfulAgent();
        Harness harness = new Harness(new ListInput(List.of("first", "second", "/exit")), agent);

        assertEquals(0, harness.loop.run());

        assertEquals(2, agent.calls);
        assertTrue(harness.err().contains("FAILED"));
        assertTrue(harness.out().contains("Assistant: OK"));
    }

    @Test
    void activeResponseCancelsTheSameUserTokenAndLoopContinues() throws Exception {
        QueueInput input = new QueueInput();
        BlockingAgent agent = new BlockingAgent();
        Harness harness = new Harness(input, agent);
        input.add("please wait");

        CompletableFuture<Integer> run = CompletableFuture.supplyAsync(harness.loop::run);
        assertTrue(agent.started.await(2, TimeUnit.SECONDS));
        assertTrue(harness.loop.cancelActiveResponse());
        assertFalse(harness.loop.cancelActiveResponse());
        input.add("reply only OK");
        input.add("/exit");

        assertEquals(0, run.get(5, TimeUnit.SECONDS));
        assertTrue(agent.cancelled);
        assertTrue(harness.out().contains("Assistant: OK"));
    }

    @Test
    void searchTextCommandInvokesGrepToolDirectly() throws Exception {
        FakeAgent agent = new FakeAgent();
        Harness harness = new Harness(new ListInput(List.of("/search-text class EchoTool", "/search-text", "/exit")), agent);
        java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath().normalize();
        com.xhlcli.tool.local.WorkspacePathResolver resolver = new com.xhlcli.tool.local.WorkspacePathResolver(root);
        com.xhlcli.tool.local.search.GrepCodeTool grepTool = new com.xhlcli.tool.local.search.GrepCodeTool(resolver);
        harness.loop.setGrepCodeTool(grepTool);

        assertEquals(0, harness.loop.run());
        assertTrue(harness.out().contains("EchoTool.java:"));
        assertTrue(harness.out().contains("用法: /search-text"));
    }

    @Test
    void routesHistoryCommands() {
        FakeAgent agent = new FakeAgent();
        Harness harness = new Harness(new ListInput(List.of("/history", "/history clear", "/exit")), agent);
        com.xhlcli.cli.terminal.SafeHistory history = new com.xhlcli.cli.terminal.SafeHistory(null);
        history.add(java.time.Instant.now(), "echo first");
        history.add(java.time.Instant.now(), "echo second");
        harness.loop.setSafeHistory(history);

        assertEquals(0, harness.loop.run());
        assertTrue(harness.out().contains("最近输入历史"));
        assertTrue(harness.out().contains("echo first"));
        assertTrue(harness.out().contains("echo second"));
        assertTrue(harness.out().contains("已清空本地历史记录"));
        assertEquals(0, history.size());
    }

    private static final class Harness {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final ByteArrayOutputStream err = new ByteArrayOutputStream();
        private final ChatLoop loop;

        private Harness(InputReader input, AgentRunner agent) {
            ChatConfig config = config();
            PlainRunRenderer renderer = new PlainRunRenderer(
                    new PrintStream(out, true, StandardCharsets.UTF_8),
                    new PrintStream(err, true, StandardCharsets.UTF_8), config.apiKey());
            loop = new ChatLoop(input, new ChatCommandParser(), agent, renderer, config);
        }

        private String out() {
            return out.toString(StandardCharsets.UTF_8);
        }

        private String err() {
            return err.toString(StandardCharsets.UTF_8);
        }
    }

    private static ChatConfig config() {
        return new ChatConfig(
                "test-key", "test-model", URI.create("https://api.deepseek.com"),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), LogLevel.WARN,
                new AgentSettings(10, Duration.ofSeconds(600)),
                Map.of(ConfigKey.API_KEY, ConfigSource.DOT_ENV, ConfigKey.MODEL, ConfigSource.DEFAULT));
    }

    private static class FakeAgent implements AgentRunner {
        private final List<String> inputs = new ArrayList<>();
        private int clearCalls;

        @Override
        public RunResult run(String input, RunEventSink events, CancellationToken token) {
            inputs.add(input);
            events.accept(new RunEvent.ModelRequestStarted(metadata(1)));
            events.accept(new RunEvent.TextDelta(metadata(2), "OK"));
            events.accept(new RunEvent.RunCompleted(metadata(3), "OK", TokenUsage.unknown()));
            return new RunResult("run", RunStatus.COMPLETED, "OK", "", 0, TokenUsage.unknown());
        }

        @Override
        public void clearHistory() {
            clearCalls++;
        }

        @Override
        public List<ChatMessage> history() {
            return List.of(ChatMessage.system("test"));
        }
    }

    private static final class BlockingAgent extends FakeAgent {
        private final CountDownLatch started = new CountDownLatch(1);
        private boolean cancelled;

        @Override
        public RunResult run(String input, RunEventSink events, CancellationToken token) {
            if ("please wait".equals(input)) {
                started.countDown();
                token.onCancel(() -> cancelled = true);
                while (!token.isCancelled()) {
                    Thread.onSpinWait();
                }
                events.accept(new RunEvent.RunCancelled(metadata(1), "USER_CANCELED"));
                return new RunResult("run", RunStatus.CANCELED, "", "USER_CANCELED", 0, TokenUsage.unknown());
            }
            return super.run(input, events, token);
        }
    }

    private static final class FailingThenSuccessfulAgent extends FakeAgent {
        private int calls;

        @Override
        public RunResult run(String input, RunEventSink events, CancellationToken token) {
            if (++calls == 1) {
                events.accept(new RunEvent.RunFailed(metadata(1), "NETWORK"));
                return new RunResult("run", RunStatus.FAILED, "", "NETWORK", 0, TokenUsage.unknown());
            }
            return super.run(input, events, token);
        }
    }

    private static RunEvent.Metadata metadata(long sequence) {
        return new RunEvent.Metadata("run", sequence, Instant.EPOCH, 0);
    }

    private record ListInput(List<String> values) implements InputReader {
        private ListInput {
            values = new ArrayList<>(values);
        }

        @Override
        public String readLine(String prompt) {
            if (values.isEmpty()) {
                throw new InputEndOfFileException();
            }
            return values.remove(0);
        }
    }

    private static final class QueueInput implements InputReader {
        private final BlockingQueue<String> values = new LinkedBlockingQueue<>();

        private void add(String value) {
            values.add(value);
        }

        @Override
        public String readLine(String prompt) {
            try {
                return values.take();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new InputInterruptedException();
            }
        }
    }
}
