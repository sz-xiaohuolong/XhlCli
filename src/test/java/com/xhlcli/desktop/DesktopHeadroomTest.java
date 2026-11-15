package com.xhlcli.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DesktopHeadroomTest {
    @TempDir Path temp;
    private ObjectNode params(String... values) {
        var node = DesktopJson.MAPPER.createObjectNode();
        for (int i = 0; i < values.length; i += 2) node.put(values[i], values[i + 1]);
        return node;
    }
    private Path project() throws Exception {
        Path project = temp.resolve("project");
        Files.createDirectories(project);
        for (int i = 0; i < 20; i++) Files.writeString(project.resolve("entry-with-a-long-name-" + i + ".txt"), "测试正文");
        return project;
    }
    private String session(DesktopService service, Path project) throws Exception {
        String projectId = service.handle("project.add", params("path", project.toString())).path("id").asText();
        return service.handle("session.create", params("projectId", projectId)).path("id").asText();
    }
    private JsonNode run(DesktopService service, BlockingQueue<ObjectNode> events, String id, String input) throws Exception {
        service.handle("turn.start", params("sessionId", id, "input", input));
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < end) {
            var event = events.poll(1, TimeUnit.SECONDS);
            if (event != null && event.path("event").asText().equals("turn.finished")) return event.path("data");
        }
        throw new AssertionError("没有收到轮次终态");
    }
    private MockResponse response(Object delta, String finish) throws Exception {
        String json = DesktopJson.MAPPER.writeValueAsString(Map.of("choices", List.of(Map.of("delta", delta, "finish_reason", finish))));
        return new MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: " + json + "\n\ndata: [DONE]\n\n");
    }
    private MockResponse toolCall() throws Exception {
        return response(Map.of("tool_calls", List.of(Map.of("index", 0, "id", "real-call", "type", "function",
                "function", Map.of("name", "list_dir", "arguments", "{\"path\":\".\"}")))), "tool_calls");
    }
    private Map<String, String> env(MockWebServer model) {
        return Map.of("DEEPSEEK_API_KEY", "test-only", "DEEPSEEK_BASE_URL", model.url("/").toString(),
                "OPENAI_API_KEY", "test-only", "OPENAI_BASE_URL", model.url("/").toString());
    }
    private void compress(MockWebServer headroom) {
        headroom.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                try {
                    var body = DesktopJson.MAPPER.readTree(request.getBody().readUtf8());
                    for (var item : body.path("messages")) if (item.path("role").asText().equals("tool"))
                        ((ObjectNode) item).put("content", "目录条目的压缩正文");
                    return new MockResponse().setBody(DesktopJson.MAPPER.createObjectNode().set("messages", body.path("messages")).toString());
                } catch (Exception failure) { return new MockResponse().setResponseCode(500); }
            }
        });
    }

    @Test void 项目配置接通真实桌面轮次且报告与原历史可重启读取() throws Exception {
        try (var model = new MockWebServer(); var headroom = new MockWebServer()) {
            model.start(); headroom.start(); compress(headroom);
            Path project = project(), home = temp.resolve("home");
            Files.writeString(project.resolve(".env"), "XHLCLI_HEADROOM_ENABLED=true\nXHLCLI_HEADROOM_URL=" + headroom.url("/") + "\n");
            model.enqueue(toolCall()); model.enqueue(response(Map.of("content", "完成"), "stop"));
            var events = new LinkedBlockingQueue<ObjectNode>(); String id;
            try (var service = new DesktopService(home, env(model), events::add)) {
                id = session(service, project);
                assertTrue(run(service, events, id, "/context").path("answer").asText().contains("尚无优化记录"));
                assertEquals("completed", run(service, events, id, "列出目录").path("status").asText());
                model.takeRequest(1, TimeUnit.SECONDS);
                var sent = DesktopJson.MAPPER.readTree(Objects.requireNonNull(model.takeRequest(1, TimeUnit.SECONDS)).getBody().readUtf8());
                assertTrue(sent.toString().contains("目录条目的压缩正文"));
                var report = service.handle("state", params()).path("sessions").get(0).path("contextOptimization");
                assertEquals("COMPRESSED", report.path("status").asText());
                assertFalse(report.toString().contains("entry-with-a-long-name"));
                String context = run(service, events, id, "/context").path("answer").asText();
                assertTrue(context.contains("已压缩"), context);
                assertTrue(context.contains("不代表账单"), context);
                assertEquals(2, model.getRequestCount());
            }
            var saved = DesktopJson.MAPPER.readTree(home.resolve(".xhlcli/desktop/state.json").toFile());
            assertTrue(saved.path("sessions").get(0).path("history").toString().contains("entry-with-a-long-name"));
            assertFalse(saved.path("sessions").get(0).path("history").toString().contains("\\\"summary\\\":\\\"目录条目的压缩正文"));
            try (var service = new DesktopService(home, env(model), events::add)) {
                assertTrue(run(service, events, id, "/context").path("answer").asText().contains("已压缩"));
                assertEquals("completed", run(service, events, id, "/model use openai:gpt-4o").path("status").asText());
                assertTrue(run(service, events, id, "/context").path("answer").asText().contains("openai:gpt-4o"));
                model.enqueue(response(Map.of("content", "切换后完成"), "stop"));
                assertEquals("completed", run(service, events, id, "继续").path("status").asText());
                headroom.takeRequest(1, TimeUnit.SECONDS);
                var compressedRequest = headroom.takeRequest(1, TimeUnit.SECONDS);
                assertNotNull(compressedRequest);
                assertEquals("gpt-4o", DesktopJson.MAPPER.readTree(compressedRequest.getBody().readUtf8()).path("model").asText());
            }
        }
    }

    @Test void 关闭优先于项目配置且旧聊天没有报告仍能查看() throws Exception {
        try (var model = new MockWebServer(); var headroom = new MockWebServer()) {
            model.start(); headroom.start();
            Path project = project();
            Files.writeString(project.resolve(".env"), "XHLCLI_HEADROOM_ENABLED=true\nXHLCLI_HEADROOM_URL=" + headroom.url("/") + "\n");
            var env = new HashMap<>(env(model)); env.put("XHLCLI_HEADROOM_ENABLED", "false");
            model.enqueue(toolCall()); model.enqueue(response(Map.of("content", "完成"), "stop"));
            var events = new LinkedBlockingQueue<ObjectNode>();
            try (var service = new DesktopService(temp.resolve("home"), env, events::add)) {
                String id = session(service, project);
                assertTrue(run(service, events, id, "/context").path("answer").asText().contains("Headroom：已关闭"));
                assertEquals("completed", run(service, events, id, "列出目录").path("status").asText());
                assertEquals(0, headroom.getRequestCount());
                String context = run(service, events, id, "/context").path("answer").asText();
                assertTrue(context.contains("已关闭"), context);
            }
        }
    }

    @Test void 非法Headroom配置在桌面运行前明确报错且不回显输入() throws Exception {
        try (var model = new MockWebServer()) {
            model.start();
            var env = new HashMap<>(env(model));
            env.put("XHLCLI_HEADROOM_ENABLED", "true");
            env.put("XHLCLI_HEADROOM_URL", "https://secret-user:secret-pass@example.com");
            model.enqueue(response(Map.of("content", "不应调用"), "stop"));
            var events = new LinkedBlockingQueue<ObjectNode>();
            try (var service = new DesktopService(temp.resolve("home"), env, events::add)) {
                String id = session(service, project());
                var result = run(service, events, id, "测试");
                assertEquals("failed", result.path("status").asText());
                assertTrue(result.path("error").asText().contains("Headroom 地址无效"));
                assertFalse(result.toString().contains("secret-pass"));
                assertEquals(0, model.getRequestCount());
            }
        }
    }
}
