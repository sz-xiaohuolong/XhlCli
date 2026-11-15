package com.xhlcli.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class HeadroomContextOptimizerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final String original = "INFO 重复的构建信息和结构化工具正文 ".repeat(40);
    private final String compact = "INFO 构建信息，共 40 条；ERROR: 必须保留的错误";

    private HeadroomContextOptimizer optimizer(MockWebServer server, int timeout) {
        return new HeadroomContextOptimizer(new HeadroomSettings(true, URI.create(server.url("/").toString()), Duration.ofMillis(timeout)), mapper);
    }

    private List<ChatMessage> history(String tool) {
        var data = mapper.createObjectNode().put("重要数据", "保持原样");
        var observation = new ToolResult("real-id", tool, ToolResultStatus.SUCCESS, original, data, 9, false, 12, "继续提示");
        return List.of(ChatMessage.system("系统规则不得外发"), ChatMessage.user("当前用户输入不得外发"),
                new ChatMessage(ChatMessage.Role.ASSISTANT, "assistant 正文不得外发", List.of(new ToolCall("real-id", tool, "{\"path\":\"秘密参数\"}")), null,
                        List.of(), "推理正文不得外发"), ChatMessage.tool("real-id", observation.observationJson(mapper)),
                ChatMessage.user("图片问题", List.of(ContentPart.text("图片问题"), ContentPart.imageBase64("AAAA", "image/png"))));
    }

    private void answer(MockWebServer server, Consumer<ArrayNode> change) {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                try {
                    var messages = (ArrayNode) mapper.readTree(request.getBody().readUtf8()).path("messages");
                    change.accept(messages);
                    return new MockResponse().setHeader("Content-Type", "application/json")
                            .setBody(mapper.createObjectNode().set("messages", messages).toString());
                } catch (Exception error) { return new MockResponse().setResponseCode(500); }
            }
        });
    }

    @Test void 压缩正文并保护完整历史与工具观察字段() throws Exception {
        try (var server = new MockWebServer()) {
            server.start(); answer(server, messages -> ((com.fasterxml.jackson.databind.node.ObjectNode) messages.get(1)).put("content", compact));
            var history = history("execute_command"); var snapshot = List.copyOf(history);
            var result = optimizer(server, 3000).optimize(history, "deepseek-flash", new CancellationToken());
            assertEquals("COMPRESSED", result.report().status().name());
            assertEquals(1, result.report().changedMessages());
            assertTrue(result.report().tokensAfter() < result.report().tokensBefore());
            assertEquals(snapshot, history);
            assertEquals(history.subList(0, 3), result.messages().subList(0, 3));
            assertEquals(history.get(4), result.messages().get(4));
            assertEquals("real-id", result.messages().get(3).toolCallId());
            var before = mapper.readTree(history.get(3).content()); var after = mapper.readTree(result.messages().get(3).content());
            assertEquals(compact, after.path("summary").asText());
            for (String key : List.of("status", "data", "elapsed_ms", "truncated", "original_chars", "continue_hint"))
                assertEquals(before.path(key), after.path(key), key);
            var sent = server.takeRequest(1, TimeUnit.SECONDS);
            assertEquals("/v1/compress", sent.getPath()); assertNull(sent.getHeader("Authorization"));
            String body = sent.getBody().readUtf8();
            assertFalse(body.contains("不得外发")); assertFalse(body.contains("秘密参数")); assertFalse(body.contains("AAAA")); assertFalse(body.contains("real-id"));
            var request = mapper.readTree(body);
            assertEquals("lossless", request.path("config").path("mode").asText());
            assertEquals(0, request.path("config").path("protect_recent").asInt(-1));
            assertEquals("deepseek-flash", request.path("model").asText());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"read_file", "write_file", "apply_patch", "git_diff", "load_skill", "revert_turn"})
    void 源码补丁和技能正文不发送给服务(String tool) throws Exception {
        try (var server = new MockWebServer()) {
            server.start(); var messages = history(tool);
            var result = optimizer(server, 3000).optimize(messages, "model", new CancellationToken());
            assertEquals("SKIPPED", result.report().status().name()); assertEquals(messages, result.messages());
            assertEquals(0, server.getRequestCount());
        }
    }

    @Test void 压缩正文后完整保留data中的高精度数值() throws Exception {
        try (var server = new MockWebServer()) {
            server.start(); answer(server, messages -> ((com.fasterxml.jackson.databind.node.ObjectNode) messages.get(1)).put("content", compact));
            var amount = new BigDecimal("12345678901234567890.123456789");
            var data = mapper.createObjectNode().put("amount", amount);
            data.putArray("nested").addObject().put("amount", amount.negate());
            var observation = new ToolResult("precise", "execute_command", ToolResultStatus.SUCCESS,
                    original, data, 9, false, 12, "继续提示").observationJson(mapper);
            var messages = List.of(ChatMessage.assistant("", List.of(new ToolCall("precise", "execute_command", "{}"))),
                    ChatMessage.tool("precise", observation));
            var result = optimizer(server, 3000).optimize(messages, "model", new CancellationToken());
            assertEquals("COMPRESSED", result.report().status().name());
            var exactReader = new ObjectMapper().reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
            var before = exactReader.readTree(observation);
            var after = exactReader.readTree(result.messages().get(1).content());
            assertEquals(amount, after.path("data").path("amount").decimalValue());
            assertEquals(amount.negate(), after.path("data").path("nested").get(0).path("amount").decimalValue());
            assertEquals(before.path("data"), after.path("data"));
            assertEquals(compact, after.path("summary").asText());
            assertFalse(mapper.isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS), "不能改动共用的 JSON 配置");
        }
    }

    @Test void 短正文与无工具调用的消息不请求服务() throws Exception {
        try (var server = new MockWebServer()) {
            server.start(); var messages = List.of(ChatMessage.system("规则"), ChatMessage.user("问题"), ChatMessage.tool("unknown", original));
            var result = optimizer(server, 3000).optimize(messages, "model", new CancellationToken());
            assertEquals(messages, result.messages()); assertEquals("SKIPPED", result.report().status().name()); assertEquals(0, server.getRequestCount());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"删除", "角色", "调用ID", "新增调用", "assistant", "非文本", "回取", "空正文"})
    void 破坏消息结构或依赖回取的响应整体回退(String fault) throws Exception {
        try (var server = new MockWebServer()) {
            server.start(); answer(server, messages -> {
                var tool = (com.fasterxml.jackson.databind.node.ObjectNode) messages.get(1);
                switch (fault) {
                    case "删除" -> messages.remove(1);
                    case "角色" -> tool.put("role", "user");
                    case "调用ID" -> tool.put("tool_call_id", "forged");
                    case "新增调用" -> tool.putArray("tool_calls");
                    case "assistant" -> ((com.fasterxml.jackson.databind.node.ObjectNode) messages.get(0)).put("content", "篡改");
                    case "非文本" -> tool.put("content", 42);
                    case "回取" -> tool.put("content", "<<ccr:hash>> headroom_retrieve");
                    case "空正文" -> tool.put("content", "");
                }
            });
            var messages = history("execute_command"); var result = optimizer(server, 3000).optimize(messages, "model", new CancellationToken());
            assertEquals(messages, result.messages()); assertEquals("FALLBACK", result.report().status().name());
        }
    }

    @Test void 没有节省时保持原始字符串() throws Exception {
        try (var server = new MockWebServer()) {
            server.start(); answer(server, messages -> {});
            var messages = history("execute_command"); var result = optimizer(server, 3000).optimize(messages, "model", new CancellationToken());
            assertEquals(messages, result.messages()); assertEquals("UNCHANGED", result.report().status().name());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"http", "json", "size", "timeout"})
    void 服务失败使用原正文且报告不泄漏响应(String fault) throws Exception {
        try (var server = new MockWebServer()) {
            server.start(); var response = new MockResponse();
            switch (fault) {
                case "http" -> response.setResponseCode(503).setBody("secret-response");
                case "json" -> response.setBody("secret-response");
                case "size" -> response.setBody("x".repeat(4 * 1024 * 1024 + 1));
                case "timeout" -> response.setSocketPolicy(SocketPolicy.NO_RESPONSE);
            }
            server.enqueue(response); var messages = history("execute_command");
            var result = optimizer(server, fault.equals("timeout") ? 150 : 3000).optimize(messages, "model", new CancellationToken());
            assertEquals(messages, result.messages()); assertEquals("FALLBACK", result.report().status().name());
            assertFalse(result.report().toString().contains("secret-response"));
        }
    }

    @Test void 禁止重定向携带正文访问其他地址() throws Exception {
        try (var server = new MockWebServer(); var target = new MockWebServer()) {
            server.start(); target.start(); target.enqueue(new MockResponse().setBody("{}"));
            server.enqueue(new MockResponse().setResponseCode(307).setHeader("Location", target.url("/stolen")));
            var messages = history("execute_command"); var result = optimizer(server, 3000).optimize(messages, "model", new CancellationToken());
            assertEquals(messages, result.messages()); assertEquals("FALLBACK", result.report().status().name()); assertEquals(0, target.getRequestCount());
        }
    }

    @Test void 服务返回503且要求立即重试时仍只发送一次() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(503).setHeader("Retry-After", "0"));
            server.enqueue(new MockResponse().setResponseCode(502));
            var result = optimizer(server, 1000).optimize(history("execute_command"), "model", new CancellationToken());
            assertEquals("HTTP_ERROR", result.report().reason());
            assertEquals(1, server.getRequestCount(), "压缩请求不得被 HTTP 客户端自动重放");
        }
    }

    @Test void 复用连接在发出请求后断开时不重发正文() throws Exception {
        try (var server = new MockWebServer()) {
            server.start(); var count = new java.util.concurrent.atomic.AtomicInteger();
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    int index = count.getAndIncrement();
                    if (index == 1) return new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST);
                    if (index > 1) return new MockResponse().setResponseCode(502);
                    try {
                        var body = mapper.readTree(request.getBody().readUtf8());
                        return new MockResponse().setBody(mapper.createObjectNode().set("messages", body.path("messages")).toString());
                    } catch (Exception failure) { return new MockResponse().setResponseCode(500); }
                }
            });
            var optimizer = optimizer(server, 1000); var messages = history("execute_command");
            assertEquals("UNCHANGED", optimizer.optimize(messages, "model", new CancellationToken()).report().status().name());
            var result = optimizer.optimize(messages, "model", new CancellationToken());
            assertEquals("NETWORK_ERROR", result.report().reason());
            assertEquals(2, server.getRequestCount(), "第二次尝试断开后不能产生第三次请求");
        }
    }

    @Test void 请求超过体积限制时不连接服务() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            var messages = List.of(ChatMessage.assistant("", List.of(new ToolCall("large", "execute_command", "{}"))),
                    ChatMessage.tool("large", "x".repeat(4 * 1024 * 1024)));
            var result = optimizer(server, 1000).optimize(messages, "model", new CancellationToken());
            assertEquals("REQUEST_LIMIT", result.report().reason());
            assertEquals(messages, result.messages());
            assertEquals(0, server.getRequestCount());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"数组", "字符串", "布尔", "空值", "未知对象"})
    void 未知JSON结构不按旧纯文本发送(String shape) throws Exception {
        try (var server = new MockWebServer()) {
            server.start(); server.enqueue(new MockResponse().setResponseCode(503));
            var observation = new ToolResult("unknown", "execute_command", ToolResultStatus.SUCCESS, original,
                    mapper.createObjectNode().put("control_marker", "DATA_MUST_NOT_BE_SENT"), 0, false, 0, "").observationJson(mapper);
            String content = switch (shape) {
                case "数组" -> "[" + observation + "]";
                case "字符串" -> mapper.writeValueAsString(original);
                case "布尔" -> "true" + " ".repeat(300);
                case "空值" -> "null" + " ".repeat(300);
                default -> mapper.createObjectNode().put("unknown", original).toString();
            };
            var messages = List.of(ChatMessage.assistant("", List.of(new ToolCall("unknown", "execute_command", "{}"))),
                    ChatMessage.tool("unknown", content));
            var result = optimizer(server, 1000).optimize(messages, "model", new CancellationToken());
            assertEquals("SKIPPED", result.report().status().name());
            assertEquals(messages, result.messages());
            assertEquals(0, server.getRequestCount(), "未知结构的 data 和控制字段不得发送");
        }
    }

    @Test void 观察JSON后附重要文本时整体跳过且不丢尾部() throws Exception {
        try (var server = new MockWebServer()) {
            server.start(); answer(server, messages -> ((com.fasterxml.jackson.databind.node.ObjectNode) messages.get(1)).put("content", compact));
            var messages = new java.util.ArrayList<>(history("execute_command"));
            messages.set(3, ChatMessage.tool("real-id", messages.get(3).content() + "\nCRITICAL_TRAILING_EVIDENCE"));
            var result = optimizer(server, 1000).optimize(messages, "model", new CancellationToken());
            assertEquals(0, server.getRequestCount(), "不能只抽取首个对象并遗失尾部证据");
            assertEquals(messages, result.messages());
            assertEquals("SKIPPED", result.report().status().name());
        }
    }

    @Test void 响应JSON后附垃圾时回退() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    try {
                        var body = mapper.readTree(request.getBody().readUtf8());
                        ((com.fasterxml.jackson.databind.node.ObjectNode) body.path("messages").get(1)).put("content", compact);
                        return new MockResponse().setBody(mapper.createObjectNode().set("messages", body.path("messages")).toString() + "\nINVALID_TRAILING_DATA");
                    } catch (Exception failure) { return new MockResponse().setResponseCode(500); }
                }
            });
            var messages = history("execute_command");
            var result = optimizer(server, 1000).optimize(messages, "model", new CancellationToken());
            assertEquals("FALLBACK", result.report().status().name());
            assertEquals("INVALID_RESPONSE", result.report().reason());
            assertEquals(messages, result.messages());
        }
    }

    @Test void 取消能中断正在等待的HTTP请求() throws Exception {
        try (var server = new MockWebServer(); var pool = Executors.newSingleThreadExecutor()) {
            server.start(); server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            var token = new CancellationToken(); var messages = history("execute_command");
            var future = pool.submit(() -> optimizer(server, 30000).optimize(messages, "model", token));
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)); token.cancel();
            var result = future.get(2, TimeUnit.SECONDS);
            assertEquals("CANCELED", result.report().status().name()); assertEquals(messages, result.messages());
        }
    }
}
