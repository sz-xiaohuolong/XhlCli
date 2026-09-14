package com.xhlcli.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.llm.LlmClient;
import com.xhlcli.llm.LlmException;
import com.xhlcli.llm.StreamListener;
import com.xhlcli.model.*;
import com.xhlcli.parallel.BoundedParallelExecutor;
import com.xhlcli.tool.ToolExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class ReactAgentParallelIntegrationTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    private ToolDefinition readDefinition() {
        return new ToolDefinition(
                "read_file",
                "read a file",
                mapper.createObjectNode().put("type", "object"),
                new ToolMetadata(ToolMetadata.RiskLevel.LOW, true, true, true, "file")
        );
    }

    private ToolDefinition writeDefinition() {
        return new ToolDefinition(
                "write_file",
                "write a file",
                mapper.createObjectNode().put("type", "object"),
                new ToolMetadata(ToolMetadata.RiskLevel.HIGH, false, true, false, "file")
        );
    }

    @Test
    void multipleReadOnlyCallsExecuteInParallelAndPreserveOrder() throws Exception {
        // 创建测试文件
        Path f1 = Files.writeString(tempDir.resolve("f1.txt"), "content 1");
        Path f2 = Files.writeString(tempDir.resolve("f2.txt"), "content 2");
        Path f3 = Files.writeString(tempDir.resolve("f3.txt"), "content 3");

        ToolCall call1 = new ToolCall("c1", "read_file", "{\"path\":\"" + f1.toString() + "\"}");
        ToolCall call2 = new ToolCall("c2", "read_file", "{\"path\":\"" + f2.toString() + "\"}");
        ToolCall call3 = new ToolCall("c3", "read_file", "{\"path\":\"" + f3.toString() + "\"}");

        // Mock LLM: 第 1 轮返回 3 个并行调用，第 2 轮给出最终回复
        List<ChatResponse> responses = List.of(
                new ChatResponse("", List.of(call1, call2, call3), new TokenUsage(10, 20, true)),
                new ChatResponse("All files read successfully.", List.of(), new TokenUsage(15, 25, true))
        );

        List<String> executionThreadNames = new CopyOnWriteArrayList<>();
        ToolExecutor mockExecutor = (call, ct) -> {
            executionThreadNames.add(Thread.currentThread().getName());
            try {
                Thread.sleep(60); // 模拟耗时
            } catch (InterruptedException ignored) {}
            ObjectNode data = JsonNodeFactory.instance.objectNode().put("path", call.argumentsJson());
            return new ToolResult(call.id(), call.name(), ToolResultStatus.SUCCESS, "Read ok", data, 60, false, 10, "");
        };

        StubLlmClient client = new StubLlmClient(responses);
        ReactAgent agent = new ReactAgent(
                ChatMessage.system("system"),
                client,
                mockExecutor,
                List.of(readDefinition()),
                new RunLimits(5, Duration.ofMinutes(1)),
                new NoopTimeoutScheduler(),
                mapper,
                Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC),
                () -> UUID.randomUUID().toString()
        );
        agent.setConcurrencyLimits(4, Duration.ofSeconds(5));

        long start = System.currentTimeMillis();
        List<RunEvent> events = new ArrayList<>();
        RunResult result = agent.run("read all", events::add, new CancellationToken());
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(RunStatus.COMPLETED, result.status());
        assertEquals("All files read successfully.", result.finalAnswer());

        // 3 个各 60ms 任务并发，总耗时应小于 150ms（远小于串行 180ms）
        assertTrue(elapsed < 160, "Expected parallel elapsed time < 160ms, actual: " + elapsed + "ms");

        // 验证 history 中工具返回顺序与 call1, call2, call3 严格一致
        List<ChatMessage> history = agent.history();
        List<ChatMessage> toolMessages = history.stream()
                .filter(m -> m.role() == ChatMessage.Role.TOOL)
                .toList();
        assertEquals(3, toolMessages.size());
        assertEquals("c1", toolMessages.get(0).toolCallId());
        assertEquals("c2", toolMessages.get(1).toolCallId());
        assertEquals("c3", toolMessages.get(2).toolCallId());
    }

    @Test
    void mixedCallsSplitIntoParallelAndSequentialBatches() {
        ToolCall read1 = new ToolCall("c1", "read_file", "{\"path\":\"a.txt\"}");
        ToolCall write = new ToolCall("c2", "write_file", "{\"path\":\"b.txt\",\"content\":\"hi\"}");
        ToolCall read2 = new ToolCall("c3", "read_file", "{\"path\":\"b.txt\"}");

        List<ChatResponse> responses = List.of(
                new ChatResponse("", List.of(read1, write, read2), TokenUsage.unknown()),
                new ChatResponse("Done mixed tasks.", List.of(), TokenUsage.unknown())
        );

        List<String> order = new ArrayList<>();
        ToolExecutor mockExecutor = (call, ct) -> {
            order.add(call.name() + ":" + call.id());
            return new ToolResult(call.id(), call.name(), ToolResultStatus.SUCCESS, "ok",
                    JsonNodeFactory.instance.objectNode(), 10, false, 2, "");
        };

        StubLlmClient client = new StubLlmClient(responses);
        ReactAgent agent = new ReactAgent(
                ChatMessage.system("system"),
                client,
                mockExecutor,
                List.of(readDefinition(), writeDefinition()),
                new RunLimits(5, Duration.ofMinutes(1)),
                new NoopTimeoutScheduler(),
                mapper,
                Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC),
                () -> UUID.randomUUID().toString()
        );
        agent.setConcurrencyLimits(4, Duration.ofSeconds(5));

        RunResult result = agent.run("mixed", event -> {}, new CancellationToken());

        assertEquals(RunStatus.COMPLETED, result.status());
        assertEquals("Done mixed tasks.", result.finalAnswer());
        assertEquals(List.of("read_file:c1", "write_file:c2", "read_file:c3"), order);
    }

    @Test
    void parallelExecutionIsolatesSingleToolTimeout() {
        ToolCall slowCall = new ToolCall("c1", "read_file", "{\"path\":\"slow.txt\"}");
        ToolCall fastCall = new ToolCall("c2", "read_file", "{\"path\":\"fast.txt\"}");

        List<ChatResponse> responses = List.of(
                new ChatResponse("", List.of(slowCall, fastCall), TokenUsage.unknown()),
                new ChatResponse("Handled timeout.", List.of(), TokenUsage.unknown())
        );

        ToolExecutor mockExecutor = (call, ct) -> {
            if ("c1".equals(call.id())) {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ignored) {}
            }
            return new ToolResult(call.id(), call.name(), ToolResultStatus.SUCCESS, "ok",
                    JsonNodeFactory.instance.objectNode(), 10, false, 2, "");
        };

        StubLlmClient client = new StubLlmClient(responses);
        ReactAgent agent = new ReactAgent(
                ChatMessage.system("system"),
                client,
                mockExecutor,
                List.of(readDefinition()),
                new RunLimits(5, Duration.ofMinutes(1)),
                new NoopTimeoutScheduler(),
                mapper,
                Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC),
                () -> UUID.randomUUID().toString()
        );
        // 单工具超时设定为 100ms
        agent.setConcurrencyLimits(4, Duration.ofMillis(100));

        RunResult result = agent.run("run timeout isolation", event -> {}, new CancellationToken());

        assertEquals(RunStatus.COMPLETED, result.status());
        assertEquals("Handled timeout.", result.finalAnswer());

        List<ChatMessage> toolMessages = agent.history().stream()
                .filter(m -> m.role() == ChatMessage.Role.TOOL)
                .toList();
        assertEquals(2, toolMessages.size());
        assertTrue(toolMessages.get(0).content().contains("timed out") || toolMessages.get(0).content().contains("timeout"));
        assertTrue(toolMessages.get(1).content().contains("success"));
    }

    private static class StubLlmClient implements LlmClient {
        private final List<ChatResponse> responses;
        private int index = 0;

        StubLlmClient(List<ChatResponse> responses) {
            this.responses = responses;
        }

        @Override
        public ChatResponse stream(
                List<ChatMessage> messages,
                List<ToolDefinition> tools,
                StreamListener listener,
                CancellationToken cancellationToken) throws LlmException {
            if (index >= responses.size()) {
                throw new IllegalStateException("No more stub responses");
            }
            ChatResponse res = responses.get(index++);
            if (listener != null && res.content() != null && !res.content().isEmpty()) {
                listener.onTextDelta(res.content());
            }
            return res;
        }
    }

    private static class NoopTimeoutScheduler implements TimeoutScheduler {
        @Override
        public Registration schedule(Duration duration, Runnable onTimeout) {
            return () -> {};
        }

        @Override
        public void close() {}
    }
}
