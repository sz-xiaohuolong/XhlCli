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
