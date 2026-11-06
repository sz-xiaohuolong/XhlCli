package com.xhlcli.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.llm.LlmClient;
import com.xhlcli.llm.LlmException;
import com.xhlcli.llm.StreamListener;
import com.xhlcli.memory.MemoryManager;
import com.xhlcli.model.*;
import com.xhlcli.plan.ExecutionPlan;
import com.xhlcli.plan.Planner;
import com.xhlcli.plan.Task;
import com.xhlcli.tool.ToolExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanExecuteAgentTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldExecutePlanSuccessfully() {
        StubLlmClient llmClient = new StubLlmClient(List.of(
                "已成功读取并核对文件内容。"
        ));

        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                null,
                List.of(),
                new StubPlanner(llmClient),
                null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                new PrintStream(new ByteArrayOutputStream())
        );

        String result = agent.run("读取测试文件并确认内容");

        assertTrue(result.contains("计划执行完成"));
        assertTrue(result.contains("已成功读取并核对文件内容"));
    }

    @Test
    void shouldCancelPlanWhenReviewHandlerReturnsCancel() {
        StubLlmClient llmClient = new StubLlmClient(List.of());
        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                null,
                List.of(),
                new StubPlanner(llmClient),
                null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.cancel(),
                new PrintStream(new ByteArrayOutputStream())
        );

        String result = agent.run("列出当前目录的文件");

        assertEquals("⏹️ 已取消本次计划执行。", result);
    }

    @Test
    void shouldReplanWhenFeedbackSuppliedDuringReview() {
        StubLlmClient llmClient = new StubLlmClient(List.of("执行带补充要求的计划"));
        final int[] reviewCount = {0};

        PlanExecuteAgent.PlanReviewHandler reviewHandler = (goal, plan) -> {
            reviewCount[0]++;
            if (reviewCount[0] == 1) {
                return PlanExecuteAgent.PlanReviewDecision.supplement("请先检查依赖");
            }
            return PlanExecuteAgent.PlanReviewDecision.execute();
        };

        StubPlanner planner = new StubPlanner(llmClient);
        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                null,
                List.of(),
                planner,
                null,
                reviewHandler,
                new PrintStream(new ByteArrayOutputStream())
        );

        String result = agent.run("构建项目");

        assertEquals(2, reviewCount[0]);
        assertTrue(planner.lastGoal.contains("补充要求：请先检查依赖"));
        assertTrue(result.contains("计划执行完成"));
    }

    @Test
    void shouldWriteResultBackToMemoryWhenCompleted() {
        Path globalDir = tempDir.resolve("global-mem");
        Path projectDir = tempDir.resolve("project-mem");
        MemoryManager memoryManager = new MemoryManager(globalDir, projectDir);

        StubLlmClient llmClient = new StubLlmClient(List.of("任务完成结果摘要"));
        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                null,
                List.of(),
                new StubPlanner(llmClient),
                memoryManager,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                new PrintStream(new ByteArrayOutputStream())
        );

        String result = agent.run("分析架构");

        assertTrue(result.contains("计划执行完成"));
        var savedMemories = memoryManager.loadAll();
        assertTrue(savedMemories.stream().anyMatch(m -> m.content().contains("用户目标: 分析架构")));
    }

    @Test
    void shouldExecuteToolCallsDuringTask() {
        List<ToolCall> calls = List.of(
                new ToolCall("call_1", "echo", "{\"message\":\"hello tool\"}")
        );

        StubLlmClient llmClient = new StubLlmClient(
                new ChatResponse("", calls, new TokenUsage(10, 5, true)),
                new ChatResponse("工具执行完毕并已验收", List.of(), new TokenUsage(20, 10, true))
        );

        ToolExecutor toolExecutor = (call, cancellationToken) -> new ToolResult(
                call.id(),
                call.name(),
                ToolResultStatus.SUCCESS,
                "echo observation: hello tool",
                new ObjectMapper().createObjectNode().put("echo", "hello tool"),
                5L,
                false,
                20,
                ""
        );

        PlanExecuteAgent agent = new PlanExecuteAgent(
                llmClient,
                toolExecutor,
                List.of(new ToolDefinition("echo", "echo tool", new ObjectMapper().createObjectNode(), ToolMetadata.conservative())),
                new StubPlanner(llmClient),
                null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(),
                new PrintStream(new ByteArrayOutputStream())
        );

        String result = agent.run("调用工具");

        assertTrue(result.contains("计划执行完成"));
        assertTrue(result.contains("工具执行完毕并已验收"));
    }

    @Test
    void preservesMarkdownBlockBoundariesInFinalAnswer() {
        String markdown = "# 标题\n\n> 引用\n\n- **重点**\n\n| 列 | 值 |\n| --- | --- |\n| 一 | 二 |\n\n```text\n代码\n```";
        var client = new StubLlmClient(List.of(markdown));
        var agent = new PlanExecuteAgent(client, null, List.of(), new StubPlanner(client), null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(), new PrintStream(new ByteArrayOutputStream()));
        var result = agent.run("生成说明", event -> {}, new CancellationToken());
        assertTrue(result.finalAnswer().contains("\n\n" + markdown));
    }

    @Test
    void toolIterationLimitDoesNotTurnNumberedToolOutputIntoSuccessfulAnswer() {
        ChatResponse call = new ChatResponse("", List.of(new ToolCall("read", "read_file", "{}")), TokenUsage.unknown());
        var client = new StubLlmClient(call, call, call, call, call);
        ToolExecutor executor = (tool, token) -> new ToolResult(tool.id(), tool.name(), ToolResultStatus.SUCCESS,
                "1 | # 标题\n2 | **原始工具输出**", new ObjectMapper().createObjectNode(), 0, false, 0, "");
        var agent = new PlanExecuteAgent(client, executor, List.of(), new StubPlanner(client), null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(), new PrintStream(new ByteArrayOutputStream()));
        var result = agent.run("验证文档", event -> {}, new CancellationToken());
        assertEquals(RunStatus.LIMIT_REACHED, result.status());
        assertTrue(!result.finalAnswer().contains("1 | #"));
        assertTrue(!result.finalAnswer().contains("计划执行完成"));
    }

    @Test
    void reachingLimitStopsLaterIndependentTasksInSequentialDesktopExecution() {
        ChatResponse call = new ChatResponse("", List.of(new ToolCall("read", "read_file", "{}")), TokenUsage.unknown());
        var client = new StubLlmClient(call, call, call, call, call,
                new ChatResponse("第二步不应被请求", List.of(), TokenUsage.unknown()));
        var planner = new Planner(client) {
            @Override public ExecutionPlan createPlan(String goal, CancellationToken token) {
                var plan = new ExecutionPlan("two-independent", goal);
                plan.addTask(new Task("task_1", "读取内容", Task.TaskType.FILE_READ));
                plan.addTask(new Task("task_2", "写入文件", Task.TaskType.FILE_WRITE));
                plan.computeExecutionOrder();return plan;
            }
        };
        ToolExecutor executor = (tool, token) -> new ToolResult(tool.id(), tool.name(), ToolResultStatus.SUCCESS,
                "读取结果", new ObjectMapper().createObjectNode(), 0, false, 0, "");
        var agent = new PlanExecuteAgent(client, executor, List.of(), planner, null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(), new PrintStream(new ByteArrayOutputStream()));
        agent.setMaxConcurrency(1);
        var result = agent.run("读取后写入", event -> {}, new CancellationToken());
        assertEquals(RunStatus.LIMIT_REACHED, result.status());
        assertEquals(1, client.responses.size(), "上限后的下一步不应发送模型请求");
    }

    private static final class StubPlanner extends Planner {
        private String lastGoal = "";

        private StubPlanner(LlmClient llmClient) {
            super(llmClient);
        }

        @Override
        public ExecutionPlan createPlan(String goal, CancellationToken cancellationToken) {
            this.lastGoal = goal;
            ExecutionPlan plan = new ExecutionPlan("plan-test", goal);
            plan.addTask(new Task("task_1", "步骤1", Task.TaskType.ANALYSIS));
            plan.computeExecutionOrder();
            return plan;
        }
    }

    private static final class StubLlmClient implements LlmClient {
        private final Queue<ChatResponse> responses = new ArrayDeque<>();

        private StubLlmClient(List<String> textResponses) {
            for (String text : textResponses) {
                responses.add(new ChatResponse(text, List.of(), new TokenUsage(10, 5, true)));
            }
        }

        private StubLlmClient(ChatResponse... customResponses) {
            responses.addAll(Arrays.asList(customResponses));
        }

        @Override
        public ChatResponse stream(List<ChatMessage> messages, List<ToolDefinition> tools,
                                   StreamListener listener, CancellationToken cancellationToken) throws LlmException {
            ChatResponse next = responses.poll();
            if (next == null) {
                return new ChatResponse("默认完成回答", List.of(), new TokenUsage(5, 5, true));
            }
            if (listener != null && next.content() != null && !next.content().isEmpty()) {
                listener.onTextDelta(next.content());
            }
            return next;
        }
    }
}
