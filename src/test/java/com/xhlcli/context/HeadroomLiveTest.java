package com.xhlcli.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.agent.*;
import com.xhlcli.llm.*;
import com.xhlcli.memory.*;
import com.xhlcli.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

/** 显式指定真实本机服务时执行；模型使用内存替身，不消耗 Provider 额度。 */
@EnabledIfEnvironmentVariable(named = "XHLCLI_HEADROOM_TEST_URL", matches = ".+")
class HeadroomLiveTest {
    @TempDir Path temp;

    @Test void 真实无损服务压缩搜索结果后能让原先超预算的Agent继续() {
        var mapper = new ObjectMapper();
        String original = IntStream.range(0, 40)
                .mapToObj(i -> "src/very/long/package/path/Example.java:" + i + ": return HEADROOM_KEEP_EVIDENCE_" + i + ";")
                .collect(Collectors.joining("\n"));
        var observation = new ToolResult("real-call", "execute_command", ToolResultStatus.SUCCESS, original,
                mapper.createObjectNode().put("exitCode", 0), 12, false, 0, "").observationJson(mapper);
        var history = List.of(ChatMessage.system("保留规则"), ChatMessage.user("前文"),
                ChatMessage.assistant("", List.of(new ToolCall("real-call", "execute_command", "{\"command\":\"不发送的真实参数\"}"))),
                ChatMessage.tool("real-call", observation));
        var budget = new TokenBudget(1000, 50, 0, 0);
        assertTrue(budget.remainingInputTokens(history, List.of()) < 0, "样本必须在压缩前超预算");
        var requests = new ArrayList<List<ChatMessage>>();
        LlmClient model = new LlmClient() {
            @Override public String modelName() { return "gpt-4o"; }
            @Override public ChatResponse stream(List<ChatMessage> messages, List<ToolDefinition> tools,
                                                  StreamListener listener, CancellationToken cancellation) {
                requests.add(List.copyOf(messages));
                return new ChatResponse("已收到全部搜索条目", TokenUsage.unknown());
            }
        };
        try (var scheduler = new ScheduledTimeoutScheduler()) {
            var agent = new ReactAgent(history.getFirst(), model, (call, token) -> { throw new AssertionError("无需新工具"); },
                    List.of(), new RunLimits(3, Duration.ofSeconds(45)), scheduler, mapper, Clock.systemUTC(), () -> "headroom-live");
            agent.setContextAssembler(new ContextAssembler(budget));
            agent.setMemoryRecall(new MemoryManager(temp.resolve("global"), temp.resolve("project")), MemoryRecallSettings.defaults());
            agent.setContextOptimizer(ContextOptimizer.fromEnvironment(Map.of(
                    "XHLCLI_HEADROOM_ENABLED", "true",
                    "XHLCLI_HEADROOM_URL", System.getenv("XHLCLI_HEADROOM_TEST_URL"),
                    "XHLCLI_HEADROOM_TIMEOUT_MS", "30000"), mapper));
            agent.restoreHistory(history);
            var result = agent.run("继续分析", event -> {}, new CancellationToken());
            var report = agent.lastContextOptimization();
            assertEquals(ContextOptimizationReport.Status.COMPRESSED, report.status(), report.toString());
            assertEquals(RunStatus.COMPLETED, result.status(), result.reason() + " " + report);
            assertEquals(1, requests.size());
            String sent = requests.getFirst().get(3).content();
            for (int i = 0; i < 40; i++) assertTrue(sent.contains(i + ": return HEADROOM_KEEP_EVIDENCE_" + i + ";"), "缺少条目 " + i);
            assertEquals(history.get(3), agent.history().get(3));
            assertTrue(report.tokensAfter() < report.tokensBefore());
            assertFalse(sent.contains("<<ccr:"));
            System.out.printf("真实 Headroom：消息 Token 估算 %d → %d，正文 %d 条，耗时 %d ms%n",
                    report.tokensBefore(), report.tokensAfter(), report.changedMessages(), report.elapsedMillis());
        }
    }
}
