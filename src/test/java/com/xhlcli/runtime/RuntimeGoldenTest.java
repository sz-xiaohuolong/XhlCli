package com.xhlcli.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.runtime.api.RuntimeApiServer;
import com.xhlcli.runtime.api.RuntimeEvent;
import com.xhlcli.runtime.api.RuntimeThreadStore;
import com.xhlcli.runtime.task.DurableTask;
import com.xhlcli.runtime.task.DurableTaskManager;
import com.xhlcli.runtime.task.TaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeGoldenTest {

    @TempDir
    Path tempDir;

    private DurableTaskManager taskManager;
    private RuntimeApiServer apiServer;
    private RuntimeThreadStore threadStore;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private static final String TEST_API_KEY = "test-sk-golden-key-987654";

    @BeforeEach
    void setUp() throws Exception {
        Path taskDbPath = tempDir.resolve("tasks").resolve("tasks.db");
        Path runtimeDbPath = tempDir.resolve("runtime").resolve("runtime.db");

        threadStore = new RuntimeThreadStore(runtimeDbPath);
        apiServer = new RuntimeApiServer(threadStore, prompt -> "Echo: " + prompt, 0, TEST_API_KEY);
        apiServer.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (taskManager != null) {
            taskManager.close();
        }
        if (apiServer != null) {
            apiServer.close();
        }
        if (threadStore != null) {
            threadStore.close();
        }
    }

    @Test
    void goldenDurableTaskLifecycleAndRecovery() throws Exception {
        Path taskDbPath = tempDir.resolve("tasks").resolve("tasks.db");
        CountDownLatch longTaskStarted = new CountDownLatch(1);
        CountDownLatch longTaskFinished = new CountDownLatch(1);

        taskManager = new DurableTaskManager(taskDbPath, prompt -> {
            if (prompt.contains("quick")) {
                return "Quick response for: " + prompt;
            }
            if (prompt.contains("long")) {
                longTaskStarted.countDown();
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                return "Long response";
            }
            return "Standard response";
        }, 2);
        taskManager.start();

        // 1. 入队快速任务并验证 Worker 执行完成
        DurableTask quickTask = taskManager.enqueue("Run quick check");
        assertNotNull(quickTask.id());
        assertEquals(TaskStatus.ENQUEUED, quickTask.status());

        // 等待执行完成
        DurableTask completedQuick = null;
        for (int i = 0; i < 50; i++) {
            completedQuick = taskManager.find(quickTask.id()).orElse(null);
            if (completedQuick != null && completedQuick.status() == TaskStatus.COMPLETED) {
                break;
            }
            Thread.sleep(50);
        }
        assertNotNull(completedQuick);
        assertEquals(TaskStatus.COMPLETED, completedQuick.status());
        assertTrue(completedQuick.result().contains("Quick response"));
        assertTrue(completedQuick.durationMs() >= 0);

        // 2. 入队长任务并在运行中触发取消
        DurableTask longTask = taskManager.enqueue("Run long computation");
        assertTrue(longTaskStarted.await(3, TimeUnit.SECONDS), "Long task should have started execution");

        boolean canceled = taskManager.cancel(longTask.id());
        assertTrue(canceled, "Cancel request should succeed");

        DurableTask canceledTask = null;
        for (int i = 0; i < 50; i++) {
            canceledTask = taskManager.find(longTask.id()).orElse(null);
            if (canceledTask != null && canceledTask.status() == TaskStatus.CANCELED) {
                break;
            }
            Thread.sleep(50);
        }
        assertNotNull(canceledTask);
        assertEquals(TaskStatus.CANCELED, canceledTask.status());

        // 3. 租约恢复与崩溃自愈验证
        // 直接在 SQLite 底层模拟一个异常停机遗留的 "running" 孤儿任务
        try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + taskDbPath);
             var ps = conn.prepareStatement("INSERT INTO runtime_tasks (id, status, prompt, created_at, started_at) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, "task_crashed_orphan_001");
            ps.setString(2, "running");
            ps.setString(3, "Crashed mid-flight operation");
            ps.setString(4, java.time.Instant.now().toString());
            ps.setString(5, java.time.Instant.now().toString());
            ps.executeUpdate();
        }

        // 关闭当前任务管理器，模拟 CLI 重启后打开新的管理器
        taskManager.close();

        DurableTaskManager recoveredManager = new DurableTaskManager(taskDbPath, prompt -> "Recovered: " + prompt, 1);
        try {
            DurableTask orphan = recoveredManager.find("task_crashed_orphan_001").orElseThrow();
            // 验证崩溃租约已自动恢复为 ENQUEUED 重新排队执行，杜绝永久悬挂阻塞在 running
            assertEquals(TaskStatus.ENQUEUED, orphan.status());
        } finally {
            recoveredManager.close();
        }
    }

    @Test
    void goldenRuntimeApiServerAndSseStream() throws Exception {
        int port = apiServer.port();
        assertTrue(port > 0);

        // 1. 未授权请求校验 (401 Unauthorized)
        HttpRequest unauthorizedReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/v1/threads"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> unauthorizedResp = httpClient.send(unauthorizedReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, unauthorizedResp.statusCode());

        // 2. 携带合法鉴权头创建线程 (200 OK)
        HttpRequest createThreadReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/v1/threads"))
                .header("Authorization", "Bearer " + TEST_API_KEY)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> createThreadResp = httpClient.send(createThreadReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, createThreadResp.statusCode());
        JsonNode threadNode = mapper.readTree(createThreadResp.body());
        String threadId = threadNode.path("id").asText();
        assertNotNull(threadId);
        assertTrue(threadId.startsWith("thread_"));

        // 3. 提交交互 turn
        HttpRequest turnReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/v1/threads/" + threadId + "/turns"))
                .header("X-XhlCLI-API-Key", TEST_API_KEY)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"prompt": "分析 pom.xml 结构"}
                        """))
                .build();
        HttpResponse<String> turnResp = httpClient.send(turnReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(202, turnResp.statusCode());
        JsonNode turnNode = mapper.readTree(turnResp.body());
        String turnId = turnNode.path("id").asText();
        assertNotNull(turnId);

        // 4. 模拟写入多条执行事件
        threadStore.appendEvent(threadId, "turn.started", mapper.writeValueAsString(java.util.Map.of("turnId", turnId)));
        threadStore.appendEvent(threadId, "message.delta", mapper.writeValueAsString(java.util.Map.of("content", "扫描中...")));
        long completedEventId = threadStore.appendEvent(threadId, "turn.completed", mapper.writeValueAsString(java.util.Map.of("turnId", turnId, "status", "completed")));

        // 5. 校验 SSE 全量流读取
        HttpRequest sseReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/v1/threads/" + threadId + "/events"))
                .header("Authorization", "Bearer " + TEST_API_KEY)
                .GET()
                .build();
        HttpResponse<String> sseResp = httpClient.send(sseReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, sseResp.statusCode());
        String sseBody = sseResp.body();
        assertTrue(sseBody.contains("event: turn.started"));
        assertTrue(sseBody.contains("event: message.delta"));
        assertTrue(sseBody.contains("event: turn.completed"));
        assertTrue(sseBody.contains("扫描中..."));

        // 6. 校验基于单调递增游标的断点拉取 (after cursor)
        HttpRequest cursorReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/v1/threads/" + threadId + "/events?after=" + (completedEventId - 1)))
                .header("Authorization", "Bearer " + TEST_API_KEY)
                .GET()
                .build();
        HttpResponse<String> cursorResp = httpClient.send(cursorReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, cursorResp.statusCode());
        String cursorBody = cursorResp.body();
        // 游标过滤后只包含最新的一条 turn.completed 事件
        assertTrue(cursorBody.contains("event: turn.completed"));
        assertFalse(cursorBody.contains("event: turn.started"));
    }
}
