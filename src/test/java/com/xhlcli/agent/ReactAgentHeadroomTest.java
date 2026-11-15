package com.xhlcli.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.context.*;
import com.xhlcli.llm.*;
import com.xhlcli.memory.*;
import com.xhlcli.model.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ReactAgentHeadroomTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String longText = "{\"重复数据\": \"保留所有内容的测试正文\"}".repeat(120);
    private final String shortText = "压缩后的构建信息；关键失败证据已保留";

    private List<ChatMessage> history() {
        return List.of(ChatMessage.system("当前规则"), ChatMessage.user("之前的问题"),
                new ChatMessage(ChatMessage.Role.ASSISTANT, "", List.of(new ToolCall("old", "execute_command", "{}")), null, List.of(), "保留推理"),
                ChatMessage.tool("old", new ToolResult("old", "execute_command", ToolResultStatus.SUCCESS,
                        longText, mapper.createObjectNode(), 1, false, 0, "").observationJson(mapper)));
    }

    private ReactAgent agent(LlmClient client, ScheduledTimeoutScheduler scheduler, int window, Duration timeout) {
        var agent = new ReactAgent(ChatMessage.system("当前规则"), client,
                (call, token) -> new ToolResult(call.id(), call.name(), ToolResultStatus.SUCCESS, longText,
                        mapper.createObjectNode(), 1, false, 0, ""), List.of(), new RunLimits(4, timeout), scheduler,
                mapper, Clock.systemUTC(), () -> "headroom-run");
        agent.setContextAssembler(new ContextAssembler(new TokenBudget(window, 50, 0, 0)));
        agent.setMemoryRecall(new MemoryManager(temp.resolve("global"), temp.resolve("project")), MemoryRecallSettings.defaults());
        return agent;
    }

    private void compress(MockWebServer server) {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                try {
                    var body = mapper.readTree(request.getBody().readUtf8());
                    for (var message : body.path("messages")) if (message.path("role").asText().equals("tool"))
                        ((com.fasterxml.jackson.databind.node.ObjectNode) message).put("content", shortText);
                    return new MockResponse().setBody(mapper.createObjectNode().set("messages", body.path("messages")).toString());
                } catch (Exception failure) { return new MockResponse().setResponseCode(500); }
            }
        });
    }

    private HeadroomContextOptimizer optimizer(MockWebServer server) {
        return new HeadroomContextOptimizer(new HeadroomSettings(true, URI.create(server.url("/").toString()), Duration.ofSeconds(3)), mapper);
    }

    @Test void 原历史超预算时先压缩再请求模型且历史保持原文() throws Exception {
        try (var server = new MockWebServer(); var scheduler = new ScheduledTimeoutScheduler()) {
            server.start(); compress(server); var requests = new ArrayList<List<ChatMessage>>();
            var agent = agent((messages, tools, listener, token) -> {
                requests.add(messages); return new ChatResponse("完成", TokenUsage.unknown());
            }, scheduler, 600, Duration.ofSeconds(10));
            agent.restoreHistory(history()); agent.setContextOptimizer(optimizer(server));
            var result = agent.run("新的问题", event -> {}, new CancellationToken());
            assertEquals(RunStatus.COMPLETED, result.status()); assertEquals(1, requests.size());
            assertTrue(requests.getFirst().get(3).content().contains(shortText));
            assertTrue(agent.history().get(3).content().contains(longText.replace("\"", "\\\"")));
            assertEquals("保留推理", requests.getFirst().get(2).reasoningContent());
            assertEquals(ChatMessage.user("新的问题"), requests.getFirst().getLast());
            assertEquals("COMPRESSED", agent.lastContextOptimization().status().name());
            agent.clearHistory(); assertNull(agent.lastContextOptimization());
        }
    }

    @Test void 工具执行后的下一轮也先压缩再检查预算() throws Exception {
        try (var server = new MockWebServer(); var scheduler = new ScheduledTimeoutScheduler()) {
            server.start(); compress(server); var requests = new ArrayList<List<ChatMessage>>();
            var agent = agent((messages, tools, listener, token) -> {
                requests.add(messages);
                return requests.size() == 1
                        ? new ChatResponse("", List.of(new ToolCall("next", "execute_command", "{}")), TokenUsage.unknown())
                        : new ChatResponse("已完成内循环", TokenUsage.unknown());
            }, scheduler, 600, Duration.ofSeconds(10));
            agent.setContextOptimizer(optimizer(server));
            var result = agent.run("执行工具", event -> {}, new CancellationToken());
            assertEquals(RunStatus.COMPLETED, result.status()); assertEquals(2, requests.size());
            assertTrue(requests.getLast().getLast().content().contains(shortText));
            assertTrue(agent.history().get(3).content().contains("重复数据"));
        }
    }

    @Test void 回退后仍超预算不会调用模型() throws Exception {
        try (var server = new MockWebServer(); var scheduler = new ScheduledTimeoutScheduler()) {
            server.start(); server.enqueue(new MockResponse().setResponseCode(503).setBody("不应进入模型的错误"));
            var agent = agent((m, t, l, c) -> { fail("超预算不得请求模型"); return null; }, scheduler, 600, Duration.ofSeconds(10));
            agent.restoreHistory(history()); agent.setContextOptimizer(optimizer(server));
            var result = agent.run("新问题", event -> {}, new CancellationToken());
            assertEquals("CONTEXT_BUDGET", result.reason()); assertEquals(RunStatus.LIMIT_REACHED, result.status());
            assertEquals("FALLBACK", agent.lastContextOptimization().status().name());
        }
    }

    @Test void 服务失败但预算足够时继续发送原文() throws Exception {
        try (var server = new MockWebServer(); var scheduler = new ScheduledTimeoutScheduler()) {
            server.start(); server.enqueue(new MockResponse().setResponseCode(503).setBody("不应进入模型的错误正文"));
            var requests = new ArrayList<List<ChatMessage>>();
            var agent = agent((m, t, l, c) -> {
                requests.add(m); return new ChatResponse("完成", TokenUsage.unknown());
            }, scheduler, 16000, Duration.ofSeconds(10));
            agent.restoreHistory(history()); agent.setContextOptimizer(optimizer(server));
            assertEquals(RunStatus.COMPLETED, agent.run("继续", event -> {}, new CancellationToken()).status());
            assertEquals(history().get(3), requests.getFirst().get(3));
            assertFalse(requests.getFirst().toString().contains("不应进入模型的错误正文"));
            assertEquals("FALLBACK", agent.lastContextOptimization().status().name());
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test void 合法压缩但规则与工具schema仍超预算时不得调用模型() throws Exception {
        try (var server = new MockWebServer(); var scheduler = new ScheduledTimeoutScheduler()) {
            server.start(); compress(server);
            var agent = agent((m, t, l, c) -> { fail("schema 不能绕过预算"); return null; }, scheduler, 600, Duration.ofSeconds(10));
            agent.setToolDefinitions(List.of(new ToolDefinition("large", "说明".repeat(1000), mapper.createObjectNode(),
                    new ToolMetadata(ToolMetadata.RiskLevel.LOW, true, false, true, "local"))));
            agent.restoreHistory(history()); agent.setContextOptimizer(optimizer(server));
            assertEquals("CONTEXT_BUDGET", agent.run("新问题", event -> {}, new CancellationToken()).reason());
            assertEquals("COMPRESSED", agent.lastContextOptimization().status().name());
        }
    }

    @Test void 取消正在压缩的请求后不进入模型() throws Exception {
        try (var server = new MockWebServer(); var scheduler = new ScheduledTimeoutScheduler(); var pool = Executors.newSingleThreadExecutor()) {
            server.start(); server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            var agent = agent((m, t, l, c) -> { fail("取消后不得请求模型"); return null; }, scheduler, 10000, Duration.ofSeconds(10));
            agent.restoreHistory(history()); agent.setContextOptimizer(optimizer(server)); var token = new CancellationToken();
            var future = pool.submit(() -> agent.run("新问题", event -> {}, token));
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)); token.cancel();
            assertEquals(RunStatus.CANCELED, future.get(2, TimeUnit.SECONDS).status());
        }
    }

    @Test void 整体运行超时能中断压缩且不调用模型() throws Exception {
        try (var server = new MockWebServer(); var scheduler = new ScheduledTimeoutScheduler()) {
            server.start(); server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            var agent = agent((m, t, l, c) -> { fail("超时后不得请求模型"); return null; }, scheduler, 10000, Duration.ofMillis(200));
            agent.restoreHistory(history()); agent.setContextOptimizer(optimizer(server));
            assertEquals("TIMEOUT", agent.run("新问题", event -> {}, new CancellationToken()).reason());
        }
    }
}
