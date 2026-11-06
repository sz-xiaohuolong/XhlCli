package com.xhlcli.runtime.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.agent.*;
import com.xhlcli.llm.*;
import com.xhlcli.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.xhlcli.runtime.task.DurableTaskIsolationTest.awaitTerminal;

class BackgroundTaskRunnerTest {
    @TempDir Path temp;
    private final RunLimits limits = new RunLimits(5, Duration.ofSeconds(8));

    private BackgroundTaskRunner runner(LlmClient client) {
        return new BackgroundTaskRunner(() -> client, temp.resolve("home"), limits, 2, Duration.ofSeconds(3), "test-secret-key");
    }

    @Test void foregroundAndTwoBackgroundTasksRunConcurrentlyWithIsolatedHistories() throws Exception {
        var entered = new CountDownLatch(3);
        var release = new CountDownLatch(1);
        var histories = new ConcurrentHashMap<String, List<ChatMessage>>();
        LlmClient client = (messages, tools, listener, token) -> {
            String prompt = messages.getLast().content();
            histories.put(prompt, List.copyOf(messages));
            entered.countDown();
            waitFor(release);
            return new ChatResponse("完成 " + prompt, TokenUsage.unknown());
        };
        try (var scheduler = new ScheduledTimeoutScheduler(); var pool = Executors.newVirtualThreadPerTaskExecutor();
             var manager = new DurableTaskManager(temp.resolve("tasks.db"), temp, runner(client), 2)) {
            var foreground = new ReactAgent(ChatMessage.system("前台"), client, (call, token) -> null, List.of(), limits,
                    scheduler, new ObjectMapper(), Clock.systemUTC(), () -> UUID.randomUUID().toString());
            manager.start();
            var one = manager.enqueue("后台一");
            var two = manager.enqueue("后台二");
            var front = pool.submit(() -> foreground.run("前台消息", e -> {}, new CancellationToken()));
            try { assertTrue(entered.await(3, TimeUnit.SECONDS), "三项任务应同时进入模型"); }
            finally { release.countDown(); }
            assertEquals(RunStatus.COMPLETED, front.get(3, TimeUnit.SECONDS).status());
            assertEquals(TaskStatus.COMPLETED, awaitTerminal(manager, one.id()).status());
            assertEquals(TaskStatus.COMPLETED, awaitTerminal(manager, two.id()).status());
            histories.forEach((prompt, history) -> assertEquals(List.of(prompt), history.stream()
                    .filter(m -> m.role() == ChatMessage.Role.USER).map(ChatMessage::content).toList()));
            assertEquals(List.of("前台消息"), foreground.history().stream()
                    .filter(m -> m.role() == ChatMessage.Role.USER).map(ChatMessage::content).toList());
        }
    }

    @Test void readsOnlyItsWorkspaceAndNeverOffersWriteTools() throws Exception {
        Files.writeString(temp.resolve("note.txt"), "项目专属内容");
        AtomicInteger requests = new AtomicInteger();
        LlmClient client = (messages, tools, listener, token) -> {
            assertTrue(tools.stream().noneMatch(t -> Set.of("write_file", "execute_command", "apply_patch", "revert_turn", "git_diff").contains(t.name())));
            if (requests.getAndIncrement() == 0) return new ChatResponse("", List.of(new ToolCall("read", "read_file", "{\"path\":\"note.txt\"}")), TokenUsage.unknown());
            assertTrue(messages.getLast().content().contains("项目专属内容"));
            return new ChatResponse("读取完成", TokenUsage.unknown());
        };
        var result = runner(client).run(new TaskExecution("read", "读取文件", temp, new CancellationToken(), text -> {}));
        assertEquals(TaskStatus.COMPLETED, result.status());
        assertEquals("读取完成", result.result());
        assertEquals(2, requests.get());
    }

    @Test void textSearchUsesJavaAndSkipsExternalSymbolicLinks() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path external = temp.resolve("outside.txt");
        Files.writeString(external, "不应泄露的外部标记");
        Files.createSymbolicLink(workspace.resolve("link.txt"), external);
        Files.writeString(workspace.resolve("inside.txt"), "项目内部标记");
        AtomicInteger requests = new AtomicInteger();
        LlmClient client = (messages, tools, listener, token) -> {
            if (requests.getAndIncrement() == 0) return new ChatResponse("", List.of(new ToolCall("grep", "grep_code",
                    "{\"pattern\":\"标记\"}")), TokenUsage.unknown());
            assertFalse(messages.getLast().content().contains("不应泄露"));
            assertTrue(messages.getLast().content().contains("项目内部标记"));
            assertTrue(messages.getLast().content().contains("java"));
            return new ChatResponse("搜索完成", TokenUsage.unknown());
        };
        assertEquals(TaskStatus.COMPLETED, runner(client).run(new TaskExecution("grep", "搜索标记", workspace,
                new CancellationToken(), s -> {})).status());
        assertEquals(2, requests.get());
    }

    @Test void rejectsFabricatedWriteAndDoesNotReportSuccess() throws Exception {
        LlmClient client = (messages, tools, listener, token) -> messages.getLast().role() == ChatMessage.Role.USER
                ? new ChatResponse("", List.of(new ToolCall("write", "write_file", "{\"path\":\"forbidden.txt\",\"content\":\"不能写\"}")), TokenUsage.unknown())
                : new ChatResponse("写入完成", TokenUsage.unknown());
        var result = runner(client).run(new TaskExecution("write", "写文件", temp, new CancellationToken(), text -> {}));
        assertEquals(TaskStatus.FAILED, result.status());
        assertTrue(result.error().contains("只读"));
        assertFalse(Files.exists(temp.resolve("forbidden.txt")));
    }

    @Test void modelFailureIsPersistedAsFailure() throws Exception {
        LlmClient client = (messages, tools, listener, token) -> { throw new LlmException(LlmErrorType.AUTHENTICATION, "鉴权失败", false, false); };
        try (var manager = new DurableTaskManager(temp.resolve("tasks.db"), temp, runner(client), 1)) {
            manager.start();
            var result = awaitTerminal(manager, manager.enqueue("故障任务").id());
            assertEquals(TaskStatus.FAILED, result.status());
            assertTrue(result.error().contains("AUTHENTICATION"));
        }
    }

    @Test void remoteCancellationReachesModelAndWaitsForItsExit() throws Exception {
        var entered = new CountDownLatch(1);
        var canceled = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        LlmClient client = (messages, tools, listener, token) -> {
            try (var registration = token.onCancel(canceled::countDown)) {
                entered.countDown();
                waitFor(release);
                return new ChatResponse("迟到答案", TokenUsage.unknown());
            }
        };
        Path db = temp.resolve("tasks.db");
        try (var owner = new DurableTaskManager(db, temp, runner(client), 1);
             var other = new DurableTaskManager(db, temp, runner(client), 1)) {
            owner.start();
            var task = owner.enqueue("取消任务");
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            try {
                assertTrue(other.cancel(task.id()));
                assertTrue(canceled.await(3, TimeUnit.SECONDS));
                assertEquals(TaskStatus.CANCEL_REQUESTED, other.find(task.id()).orElseThrow().status());
                assertTrue(other.cancel(task.id()));
            } finally { release.countDown(); }
            assertEquals(TaskStatus.CANCELED, awaitTerminal(other, task.id()).status());
            assertFalse(other.find(task.id()).orElseThrow().result().contains("迟到答案"));
        }
    }

    @Test void streamsRedactedProgressAndPersistsFinalAnswerAcrossRestart() throws Exception {
        var streamed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        LlmClient client = (messages, tools, listener, token) -> {
            listener.onTextDelta("当前结果 test-secret-key " + "内容".repeat(100));
            streamed.countDown();
            waitFor(release);
            return new ChatResponse("最终结果 test-secret-key", TokenUsage.unknown());
        };
        Path db = temp.resolve("tasks.db");
        String id;
        try (var manager = new DurableTaskManager(db, temp, runner(client), 1)) {
            manager.start();
            id = manager.enqueue("流式任务").id();
            try {
                assertTrue(streamed.await(3, TimeUnit.SECONDS));
                assertTrue(manager.find(id).orElseThrow().result().contains("当前结果"));
                assertFalse(manager.find(id).orElseThrow().result().contains("test-secret-key"));
            } finally { release.countDown(); }
            assertEquals("最终结果 ***", awaitTerminal(manager, id).result());
        }
        try (var reopened = new DurableTaskManager(db, temp, runner(client), 1)) {
            assertEquals("最终结果 ***", reopened.find(id).orElseThrow().result());
        }
    }

    @Test void oversizedContextFailsBeforeAnyModelOrSummaryRequest() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        LlmClient client = (messages, tools, listener, token) -> {
            requests.incrementAndGet();
            return new ChatResponse("不应请求模型", TokenUsage.unknown());
        };
        var result = runner(client).run(new TaskExecution("oversized", "内容".repeat(200_000), temp,
                new CancellationToken(), s -> {}));
        assertEquals(TaskStatus.FAILED, result.status());
        assertTrue(result.error().contains("上下文"));
        assertEquals(0, requests.get(), "超预算不能触发摘要或主模型请求");
    }

    @Test void overallTimeoutReachesModelAndIsNotCompleted() throws Exception {
        var canceled = new CountDownLatch(1);
        LlmClient client = (messages, tools, listener, token) -> {
            try (var registration = token.onCancel(canceled::countDown)) {
                waitFor(canceled);
                return new ChatResponse("超时后的答案", TokenUsage.unknown());
            }
        };
        var shortRunner = new BackgroundTaskRunner(() -> client, temp.resolve("home"),
                new RunLimits(5, Duration.ofMillis(200)), 2, Duration.ofSeconds(1), "test-secret-key");
        var result = shortRunner.run(new TaskExecution("timeout", "等待超时", temp, new CancellationToken(), s -> {}));
        assertEquals(0, canceled.getCount());
        assertEquals(TaskStatus.FAILED, result.status());
        assertFalse(result.result().contains("超时后的答案"));
    }

    @Test void canceledBeforeStartMakesNoModelRequest() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        LlmClient client = (messages, tools, listener, token) -> { calls.incrementAndGet(); return new ChatResponse("不应执行", TokenUsage.unknown()); };
        var token = new CancellationToken();
        token.cancel();
        assertEquals(TaskStatus.CANCELED, runner(client).run(new TaskExecution("canceled", "不执行", temp, token, s -> {})).status());
        assertEquals(0, calls.get());
    }

    private static void waitFor(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("测试放行超时"); }
        catch (InterruptedException e) { throw new AssertionError(e); }
    }
}
