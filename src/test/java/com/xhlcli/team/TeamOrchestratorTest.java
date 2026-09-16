package com.xhlcli.team;

import com.xhlcli.llm.CancellationToken;
import com.xhlcli.llm.LlmClient;
import com.xhlcli.llm.LlmErrorType;
import com.xhlcli.llm.LlmException;
import com.xhlcli.llm.StreamListener;
import com.xhlcli.model.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.*;

class TeamOrchestratorTest {

    @Test
    void testFullThreePhaseExecutionSuccess() {
        // 1. Planner 返回 2 步计划
        String planJson = """
                ```json
                {
                  "summary": "重构工具执行模块",
                  "steps": [
                    {
                      "id": "step_1",
                      "description": "阅读并分析现有接口定义",
                      "type": "FILE_READ",
                      "dependencies": []
                    },
                    {
                      "id": "step_2",
                      "description": "实现新特性并编写单测",
                      "type": "FILE_WRITE",
                      "dependencies": ["step_1"]
                    }
                  ]
                }
                ```
                """;
        String workerStep1 = "已完成接口分析，确定方案。";
        String reviewStep1 = "{\"approved\": true, \"summary\": \"分析详尽，准予通过\", \"issues\": []}";
        String workerStep2 = "已完成新特性实现及单元测试。";
        String reviewStep2 = "{\"approved\": true, \"summary\": \"代码质量良好，单测全部通过\", \"issues\": []}";

        QueueLlmClient llm = new QueueLlmClient(planJson, workerStep1, reviewStep1, workerStep2, reviewStep2);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(baos, true, StandardCharsets.UTF_8);

        TeamOrchestrator orchestrator = new TeamOrchestrator(llm, null, List.of(), null, out);
        RunResult result = orchestrator.run("请重构工具执行模块", event -> {}, new CancellationToken());

        assertEquals(RunStatus.COMPLETED, result.status());
        assertTrue(result.finalAnswer().contains("Multi-Agent 协同执行看板"));
        assertTrue(result.finalAnswer().contains("step_1"));
        assertTrue(result.finalAnswer().contains("step_2"));
        assertTrue(result.finalAnswer().contains("✅ 成功"));
    }

    @Test
    void testReviewFeedbackAndRetrySuccess() {
        String planJson = """
                {
                  "summary": "修复并发死锁",
                  "steps": [
                    {
                      "id": "step_1",
                      "description": "添加读写锁并消除锁争用",
                      "type": "CODE",
                      "dependencies": []
                    }
                  ]
                }
                """;
        String workerAttempt1 = "简单加了 synchronized 关键字。";
        String reviewReject = """
                {
                  "approved": false,
                  "summary": "粗粒度锁会导致性能下降",
                  "issues": ["synchronized 会降低吞吐量，要求使用 ReentrantReadWriteLock"],
                  "suggestions": ["改用 ReentrantReadWriteLock"]
                }
                """;
        String workerAttempt2 = "已改为 ReentrantReadWriteLock 细粒度读写分离锁。";
        String reviewApprove = """
                {
                  "approved": true,
                  "summary": "符合读写分离要求",
                  "issues": []
                }
                """;

        QueueLlmClient llm = new QueueLlmClient(planJson, workerAttempt1, reviewReject, workerAttempt2, reviewApprove);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(baos, true, StandardCharsets.UTF_8);

        TeamOrchestrator orchestrator = new TeamOrchestrator(llm, null, List.of(), null, out, 2);
        RunResult result = orchestrator.run("修复并发死锁", event -> {}, new CancellationToken());

        assertEquals(RunStatus.COMPLETED, result.status());
        String logs = baos.toString(StandardCharsets.UTF_8);
        assertTrue(logs.contains("打回重试 1/2"));
        assertTrue(logs.contains("ReentrantReadWriteLock"));
        assertTrue(logs.contains("审查通过"));
    }

    @Test
    void testCircuitBreakerWhenExceedingMaxRetries() {
        String planJson = """
                {
                  "summary": "实现复杂算法",
                  "steps": [
                    {
                      "id": "step_1",
                      "description": "编写高性能排序算法",
                      "type": "CODE",
                      "dependencies": []
                    }
                  ]
                }
                """;
        String workerAttempt1 = "实现版本 1";
        String reviewReject1 = "{\"approved\": false, \"summary\": \"时间复杂度过高\", \"issues\": [\"O(N^2)\"]}";
        String workerAttempt2 = "实现版本 2";
        String reviewReject2 = "{\"approved\": false, \"summary\": \"空间复杂度超标\", \"issues\": [\"O(N^2) space\"]}";
        String workerAttempt3 = "实现版本 3";
        String reviewReject3 = "{\"approved\": false, \"summary\": \"依然不合规\", \"issues\": [\"未能达到 O(N log N)\"]}";

        QueueLlmClient llm = new QueueLlmClient(
                planJson,
                workerAttempt1, reviewReject1,
                workerAttempt2, reviewReject2,
                workerAttempt3, reviewReject3
        );

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(baos, true, StandardCharsets.UTF_8);

        TeamOrchestrator orchestrator = new TeamOrchestrator(llm, null, List.of(), null, out, 2);
        RunResult result = orchestrator.run("编写高性能排序算法", event -> {}, new CancellationToken());

        assertEquals(RunStatus.FAILED, result.status());
        String logs = baos.toString(StandardCharsets.UTF_8);
        assertTrue(logs.contains("触发熔断保护"));
        assertTrue(result.finalAnswer().contains("❌ 失败"));
    }

    @Test
    void testIndependentStepsParallelExecution() {
        // 3 个无依赖的步骤，同时分配给 2 个 Worker 池并行执行
        String planJson = """
                {
                  "summary": "并行检查多模块",
                  "steps": [
                    {
                      "id": "step_1",
                      "description": "检查前端配置",
                      "type": "CHECK",
                      "dependencies": []
                    },
                    {
                      "id": "step_2",
                      "description": "检查后端配置",
                      "type": "CHECK",
                      "dependencies": []
                    },
                    {
                      "id": "step_3",
                      "description": "检查CI配置",
                      "type": "CHECK",
                      "dependencies": []
                    }
                  ]
                }
                """;
        String approveReview = "{\"approved\": true, \"summary\": \"检查合格\", \"issues\": []}";

        // 动态支持任意数量返回
        LlmClient llm = new LlmClient() {
            private final Queue<String> planQueue = new ArrayDeque<>(List.of(planJson));

            @Override
            public ChatResponse stream(
                    List<ChatMessage> messages,
                    List<ToolDefinition> tools,
                    StreamListener listener,
                    CancellationToken cancellationToken) {
                if (!planQueue.isEmpty()) {
                    String p = planQueue.poll();
                    if (listener != null) listener.onTextDelta(p);
                    return new ChatResponse(p, List.of(), new TokenUsage(10, 10, true));
                }
                ChatMessage last = messages.get(messages.size() - 1);
                String resp;
                if (last.content().contains("待审查步骤任务")) {
                    resp = approveReview;
                } else {
                    resp = "检查完成，一切正常。";
                }
                if (listener != null) {
                    listener.onTextDelta(resp);
                }
                return new ChatResponse(resp, List.of(), new TokenUsage(5, 5, true));
            }
        };

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(baos, true, StandardCharsets.UTF_8);

        TeamOrchestrator orchestrator = new TeamOrchestrator(llm, null, List.of(), null, out);
        RunResult result = orchestrator.run("并行检查多模块", event -> {}, new CancellationToken());

        assertEquals(RunStatus.COMPLETED, result.status());
        String logs = baos.toString(StandardCharsets.UTF_8);
        assertTrue(logs.contains("3 个独立步骤并行执行"));
        assertTrue(result.finalAnswer().contains("step_1"));
        assertTrue(result.finalAnswer().contains("step_2"));
        assertTrue(result.finalAnswer().contains("step_3"));
    }

    @Test
    void testCancellationImmediatelyStops() {
        QueueLlmClient llm = new QueueLlmClient("dummy");
        TeamOrchestrator orchestrator = new TeamOrchestrator(llm);

        CancellationToken token = new CancellationToken();
        token.cancel();

        RunResult result = orchestrator.run("任务", event -> {}, token);
        assertEquals(RunStatus.CANCELED, result.status());
        assertTrue(result.finalAnswer().contains("取消"));
    }

    private static final class QueueLlmClient implements LlmClient {
        private final Queue<String> responses = new ConcurrentLinkedQueue<>();

        private QueueLlmClient(String... textResponses) {
            responses.addAll(Arrays.asList(textResponses));
        }

        @Override
        public ChatResponse stream(
                List<ChatMessage> messages,
                List<ToolDefinition> tools,
                StreamListener listener,
                CancellationToken cancellationToken) throws LlmException {
            if (cancellationToken != null && cancellationToken.isCancelled()) {
                throw new LlmException(LlmErrorType.CANCELLED, "Cancelled", false, false);
            }
            String next = responses.poll();
            if (next == null) {
                next = "{\"approved\": true, \"summary\": \"OK\", \"issues\": []}";
            }
            if (listener != null) {
                listener.onTextDelta(next);
            }
            return new ChatResponse(next, List.of(), new TokenUsage(10, 5, true));
        }
    }
}
