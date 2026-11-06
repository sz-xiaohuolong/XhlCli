package com.xhlcli.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.context.ContextAssembler;
import com.xhlcli.context.TokenBudget;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.llm.LlmClient;
import com.xhlcli.memory.*;
import com.xhlcli.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MemoryRecallIntegrationTest {
    @TempDir Path dir;

    @Test
    void reusesOneRecallPerRunRefreshesNextRunAndNeverCommitsReferenceBlock() {
        AtomicInteger reads = new AtomicInteger();
        var memory = new MemoryManager(dir.resolve("global"), dir.resolve("project")) {
            @Override public MemoryRecallResult recall(MemoryQuery query) {
                reads.incrementAndGet();
                return super.recall(query);
            }
        };
        memory.saveProject("Redis 最初部署复杂", "user");
        memory.saveProject("Java 21", "user");
        List<List<ChatMessage>> requests = new ArrayList<>();
        LlmClient client = (messages, tools, listener, token) -> {
            requests.add(List.copyOf(messages));
            if (requests.size() == 1) return new ChatResponse("", List.of(new ToolCall("call1", "remember", "{}")), TokenUsage.unknown());
            return new ChatResponse("完成", TokenUsage.unknown());
        };
        try (var scheduler = new ScheduledTimeoutScheduler()) {
            ReactAgent agent = new ReactAgent(ChatMessage.system("必须保留的项目规则"), client, (call, token) -> {
                memory.saveProject("Redis 新增运维结论", "user");
                return new ToolResult(call.id(), call.name(), ToolResultStatus.SUCCESS, "已保存", new ObjectMapper().createObjectNode(), 0, false, 0, "");
            }, List.of(), new RunLimits(5, Duration.ofSeconds(10)), scheduler, new ObjectMapper(), Clock.systemUTC(), () -> UUID.randomUUID().toString());
            agent.setContextAssembler(new ContextAssembler(new TokenBudget(10000)));
            agent.setMemoryRecall(memory, MemoryRecallSettings.defaults());
            assertEquals(RunStatus.COMPLETED, agent.run("Redis", e -> {}, new CancellationToken()).status());
            assertEquals(1, reads.get());
            assertEquals(2, requests.size());
            for (var request : requests) {
                assertTrue(text(request).contains("Redis 最初部署复杂"));
                assertFalse(text(request).contains("Redis 新增运维结论"));
                assertFalse(text(request).contains("Java 21"));
                assertEquals("必须保留的项目规则", request.getFirst().content());
                assertEquals(ChatMessage.Role.USER, request.get(1).role());
            }
            assertFalse(text(agent.history()).contains("历史参考资料"));
            assertFalse(text(agent.history()).contains("Redis 最初部署复杂"));
            assertEquals(RunStatus.COMPLETED, agent.run("Redis", e -> {}, new CancellationToken()).status());
            assertEquals(2, reads.get());
            assertEquals(3, requests.size());
            assertTrue(text(requests.getLast()).contains("Redis 新增运维结论"));
            agent.clearHistory();
            assertNull(agent.lastMemoryRecall());
            assertEquals(3, memory.loadAll().size());
        }
    }

    @Test
    void queryUsesOnlyRecentUserMessagesAndReadFailureDoesNotBreakConversation() throws Exception {
        var memory = new MemoryManager(dir.resolve("global"), dir.resolve("project"));
        memory.saveProject("Redis 决策", "user");
        List<List<ChatMessage>> requests = new ArrayList<>();
        LlmClient client = (messages, tools, listener, token) -> {
            requests.add(List.copyOf(messages));
            return new ChatResponse("无关助手输出 PostgreSQL", TokenUsage.unknown());
        };
        try (var scheduler = new ScheduledTimeoutScheduler()) {
            var agent = agent(client, scheduler, memory, new TokenBudget(10000));
            agent.run("Redis", e -> {}, new CancellationToken());
            agent.run("刚才那个为什么没用？", e -> {}, new CancellationToken());
            assertEquals(1, agent.lastMemoryRecall().selected().size());
            Files.delete(dir.resolve("project/long_term.jsonl"));
            Files.createDirectory(dir.resolve("project/long_term.jsonl"));
            assertEquals(RunStatus.COMPLETED, agent.run("Redis", e -> {}, new CancellationToken()).status());
            assertTrue(agent.lastMemoryRecall().readFailed());
            assertFalse(text(requests.getLast()).contains("历史参考资料"));
        }
    }

    @Test
    void reducesMemoryForRealSystemToolAndConversationBudget() {
        var memory = new MemoryManager(dir.resolve("global"), dir.resolve("project"));
        for (int i = 0; i < 6; i++) memory.saveProject("Redis 决策 " + i + " 部署复杂".repeat(15), "user");
        var budget = new TokenBudget(500, 100, 0, 0);
        List<List<ChatMessage>> requests = new ArrayList<>();
        var tools = List.of(new ToolDefinition("read", "工具说明".repeat(50), new ObjectMapper().createObjectNode(), new ToolMetadata(ToolMetadata.RiskLevel.LOW, true, true, false, "")));
        LlmClient client = (messages, definitions, listener, token) -> {
            requests.add(List.copyOf(messages));
            assertTrue(budget.remainingInputTokens(messages, definitions) >= 0);
            return new ChatResponse("完成", TokenUsage.unknown());
        };
        try (var scheduler = new ScheduledTimeoutScheduler()) {
            var agent = agent(client, scheduler, memory, budget);
            agent.setToolDefinitions(tools);
            assertEquals(RunStatus.COMPLETED, agent.run("Redis", e -> {}, new CancellationToken()).status());
            assertFalse(agent.lastMemoryRecall().selected().isEmpty());
            assertTrue(agent.lastMemoryRecall().selected().size() < 5);
            assertTrue(agent.lastMemoryRecall().budgetLimited());
            assertEquals("Redis", requests.getFirst().getLast().content());
        }
    }

    @Test
    void toolResultGrowthOnlyShrinksCurrentRecallAndKeepsProtocolHistory() {
        var memory = new MemoryManager(dir.resolve("global"), dir.resolve("project"));
        for (int i = 0; i < 5; i++) memory.saveProject("Redis 记录 " + i + " 部署决定".repeat(10), "user");
        var budget = new TokenBudget(1100, 100, 0, 0);
        List<List<ChatMessage>> requests = new ArrayList<>();
        LlmClient client = (messages, tools, listener, token) -> {
            requests.add(List.copyOf(messages));
            assertTrue(budget.remainingInputTokens(messages, tools) >= 0);
            return requests.size() == 1
                    ? new ChatResponse("", List.of(new ToolCall("growth", "read", "{}")), TokenUsage.unknown())
                    : new ChatResponse("完成", TokenUsage.unknown());
        };
        try (var scheduler = new ScheduledTimeoutScheduler()) {
            var agent = new ReactAgent(ChatMessage.system("必须保留规则"), client,
                    (call, token) -> new ToolResult(call.id(), call.name(), ToolResultStatus.SUCCESS,
                            "工具结果".repeat(420), new ObjectMapper().createObjectNode(), 0, false, 0, ""), List.of(),
                    new RunLimits(5, Duration.ofSeconds(10)), scheduler, new ObjectMapper(), Clock.systemUTC(), () -> UUID.randomUUID().toString());
            agent.setContextAssembler(new ContextAssembler(budget));
            agent.setMemoryRecall(memory, MemoryRecallSettings.defaults());
            assertEquals(RunStatus.COMPLETED, agent.run("Redis", e -> {}, new CancellationToken()).status());
            assertEquals(2, requests.size());
            assertTrue(agent.lastMemoryRecall().budgetLimited());
            assertTrue(agent.lastMemoryRecall().selected().size() < 5);
            assertTrue(requests.getLast().stream().anyMatch(m -> m.role() == ChatMessage.Role.USER && m.content().equals("Redis")));
            assertEquals(ChatMessage.Role.TOOL, requests.getLast().getLast().role());
            assertFalse(text(agent.history()).contains("历史参考资料"));
        }
    }

    @Test
    void freezesToolDefinitionsForBudgetAssemblyAndSending() {
        var memory = new MemoryManager(dir.resolve("global"), dir.resolve("project"));
        memory.saveProject("Redis 决策", "user");
        var budget = new TokenBudget(500, 100, 0, 0);
        AtomicInteger calls = new AtomicInteger();
        LlmClient client = (messages, tools, listener, token) -> {
            calls.incrementAndGet();
            assertTrue(budget.remainingInputTokens(messages, tools) >= 0, "发出的工具集必须与已校验的快照一致");
            return new ChatResponse("完成", TokenUsage.unknown());
        };
        try (var scheduler = new ScheduledTimeoutScheduler()) {
            var agent = agent(client, scheduler, memory, budget);
            agent.setContextAssembler(new ContextAssembler(budget) {
                @Override public RecalledContext withRecall(List<ChatMessage> history, List<ToolDefinition> tools, MemoryRecallResult recalled) {
                    var result = super.withRecall(history, tools, recalled);
                    agent.setToolDefinitions(List.of(new ToolDefinition("mcp_new", "新增工具说明".repeat(1000), new ObjectMapper().createObjectNode(),
                            new ToolMetadata(ToolMetadata.RiskLevel.LOW, true, true, false, ""))));
                    return result;
                }
            });
            assertEquals(RunStatus.COMPLETED, agent.run("Redis", e -> {}, new CancellationToken()).status());
            assertEquals(1, calls.get());
            var lastSent = agent.lastMemoryRecall();
            assertEquals(RunStatus.LIMIT_REACHED, agent.run("Redis", e -> {}, new CancellationToken()).status());
            assertEquals(1, calls.get(), "下一轮应使用新工具定义并拒绝超限请求");
            assertSame(lastSent, agent.lastMemoryRecall(), "未发送的新一轮不能覆盖最近实际注入诊断");
        }
    }

    @Test
    void budgetRejectionKeepsLastActuallySentRecallForDiagnostics() {
        var memory = new MemoryManager(dir.resolve("global"), dir.resolve("project"));
        memory.saveProject("Redis 决策", "user");
        AtomicInteger calls = new AtomicInteger();
        LlmClient client = (messages, tools, listener, token) -> {
            calls.incrementAndGet();
            return new ChatResponse("", List.of(new ToolCall("large", "read", "{}")), TokenUsage.unknown());
        };
        try (var scheduler = new ScheduledTimeoutScheduler()) {
            var agent = new ReactAgent(ChatMessage.system("规则"), client,
                    (call, token) -> new ToolResult(call.id(), call.name(), ToolResultStatus.SUCCESS,
                            "工具结果".repeat(1000), new ObjectMapper().createObjectNode(), 0, false, 0, ""), List.of(),
                    new RunLimits(5, Duration.ofSeconds(10)), scheduler, new ObjectMapper(), Clock.systemUTC(), () -> UUID.randomUUID().toString());
            agent.setContextAssembler(new ContextAssembler(new TokenBudget(500, 100, 0, 0)));
            agent.setMemoryRecall(memory, MemoryRecallSettings.defaults());
            assertEquals(RunStatus.LIMIT_REACHED, agent.run("Redis", e -> {}, new CancellationToken()).status());
            assertEquals(1, calls.get());
            assertEquals(1, agent.lastMemoryRecall().selected().size());
        }
    }

    @Test
    void oversizedBaseRequestStopsWithoutCallingModelOrDiscardingCurrentQuestion() {
        var memory = new MemoryManager(dir.resolve("global"), dir.resolve("project"));
        AtomicInteger calls = new AtomicInteger();
        LlmClient client = (messages, tools, listener, token) -> { calls.incrementAndGet(); return new ChatResponse("不应调用", TokenUsage.unknown()); };
        try (var scheduler = new ScheduledTimeoutScheduler()) {
            var agent = agent(client, scheduler, memory, new TokenBudget(100, 50, 0, 0));
            var result = agent.run("必须保留问题".repeat(100), e -> {}, new CancellationToken());
            assertEquals(RunStatus.LIMIT_REACHED, result.status());
            assertEquals("CONTEXT_BUDGET", result.reason());
            assertEquals(0, calls.get());
            assertEquals(1, agent.history().size());
        }
    }

    @Test
    void recallRuntimeFailureFallsBackToNoMemoryInsteadOfAllRecords() {
        var memory = new MemoryManager(dir.resolve("global"), dir.resolve("project")) {
            @Override public MemoryRecallResult recall(MemoryQuery query) { throw new IllegalStateException("private data"); }
        };
        memory.saveProject("Redis 不应泄露", "user");
        LlmClient client = (messages, tools, listener, token) -> {
            assertFalse(text(messages).contains("不应泄露"));
            return new ChatResponse("继续", TokenUsage.unknown());
        };
        try (var scheduler = new ScheduledTimeoutScheduler()) {
            var agent = agent(client, scheduler, memory, new TokenBudget(10000));
            assertEquals(RunStatus.COMPLETED, agent.run("Redis", e -> {}, new CancellationToken()).status());
            assertTrue(agent.lastMemoryRecall().readFailed());
            assertFalse(agent.lastMemoryRecall().diagnostics().toString().contains("private data"));
        }
    }

    private ReactAgent agent(LlmClient client, ScheduledTimeoutScheduler scheduler, MemoryManager memory, TokenBudget budget) {
        var agent = new ReactAgent(ChatMessage.system("必须保留的项目规则"), client,
                (call, token) -> { throw new AssertionError("本场景不需要工具"); }, List.of(),
                new RunLimits(5, Duration.ofSeconds(10)), scheduler, new ObjectMapper(), Clock.systemUTC(), () -> UUID.randomUUID().toString());
        agent.setContextAssembler(new ContextAssembler(budget));
        agent.setMemoryRecall(memory, MemoryRecallSettings.defaults());
        return agent;
    }

    private static String text(List<ChatMessage> messages) { return messages.stream().map(ChatMessage::content).reduce("", (a, b) -> a + "\n" + b); }
}
