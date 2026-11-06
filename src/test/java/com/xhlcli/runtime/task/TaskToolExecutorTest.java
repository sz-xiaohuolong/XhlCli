package com.xhlcli.runtime.task;

import com.xhlcli.agent.ScheduledTimeoutScheduler;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.*;
import com.xhlcli.parallel.BoundedParallelExecutor;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class TaskToolExecutorTest {
    @Test void closeWaitsForRealToolExitAndRejectsLateStarts() throws Exception {
        var started = new CountDownLatch(1);
        var canceled = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try (var scheduler = new ScheduledTimeoutScheduler(); var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var scope = new TaskToolExecutor((call, token) -> {
                calls.incrementAndGet();
                try (var registration = token.onCancel(canceled::countDown)) {
                    started.countDown();
                    awaitUninterruptibly(release);
                    return BoundedParallelExecutor.failureResult(call, ToolResultStatus.SUCCESS, "完成", 0);
                }
            }, new CancellationToken(), Duration.ofSeconds(5), scheduler);
            var active = threads.submit(() -> scope.execute(new ToolCall("one", "read_file", "{}"), new CancellationToken()));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var closing = threads.submit(scope::close);
            try {
                assertTrue(canceled.await(2, TimeUnit.SECONDS));
                assertFalse(closing.isDone(), "在途工具没有退出时不能确认停止");
                assertEquals(ToolResultStatus.CANCELLED, scope.execute(new ToolCall("two", "read_file", "{}"), new CancellationToken()).status());
                assertEquals(1, calls.get());
            } finally { release.countDown(); }
            closing.get(2, TimeUnit.SECONDS);
            assertEquals(ToolResultStatus.CANCELLED, active.get(2, TimeUnit.SECONDS).status());
        }
    }

    @Test void timeoutCancelsUnderlyingToolInsteadOfOnlyCompletingFuture() throws Exception {
        var canceled = new CountDownLatch(1);
        try (var scheduler = new ScheduledTimeoutScheduler();
             var scope = new TaskToolExecutor((call, token) -> {
                 try (var registration = token.onCancel(canceled::countDown)) {
                     try { canceled.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                     return BoundedParallelExecutor.failureResult(call, ToolResultStatus.SUCCESS, "完成", 0);
                 }
             }, new CancellationToken(), Duration.ofMillis(50), scheduler)) {
            assertEquals(ToolResultStatus.TIMEOUT, scope.execute(new ToolCall("one", "read_file", "{}"), new CancellationToken()).status());
            assertEquals(0, canceled.getCount());
        }
    }

    static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("清理放行超时"); break; }
            catch (InterruptedException e) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
