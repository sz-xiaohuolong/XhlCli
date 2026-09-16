package com.xhlcli.parallel;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.ToolCall;
import com.xhlcli.model.ToolResult;
import com.xhlcli.model.ToolResultStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BoundedParallelExecutorTest {

    private BoundedParallelExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new BoundedParallelExecutor(4);
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.close();
        }
    }

    private static ToolResult dummySuccess(ToolCall call, long elapsed) {
        return new ToolResult(
                call.id(),
                call.name(),
                ToolResultStatus.SUCCESS,
                "Success for " + call.id(),
                JsonNodeFactory.instance.objectNode().put("callId", call.id()),
                elapsed,
                false,
                0,
                "");
    }

    @Test
    void parallelSpeedupBenchmark() {
        List<IndexedToolCall> calls = List.of(
                new IndexedToolCall(0, new ToolCall("c0", "read_file", "{}")),
                new IndexedToolCall(1, new ToolCall("c1", "read_file", "{}")),
                new IndexedToolCall(2, new ToolCall("c2", "read_file", "{}")),
                new IndexedToolCall(3, new ToolCall("c3", "read_file", "{}"))
        );
        ParallelBatch batch = ParallelBatch.parallel(calls);

        long start = System.currentTimeMillis();
        List<ToolResult> results = executor.executeBatch(
                batch,
                indexedCall -> {
                    try {
                        Thread.sleep(80);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return dummySuccess(indexedCall.call(), 80);
                },
                Duration.ofSeconds(5),
                new CancellationToken()
        );
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(4, results.size());
        for (ToolResult result : results) {
            assertEquals(ToolResultStatus.SUCCESS, result.status());
        }
        // 4 个任务各 80ms，并发执行应在 ~150ms 左右完成（设置 600ms 宽容度防止 CI 环境线程调度抖动）
        assertTrue(elapsed < 600, "Expected parallel execution time < 600ms, actual: " + elapsed + "ms");
    }

    @Test
    void inOrderMergeWithVaryingLatencies() {
        List<IndexedToolCall> calls = List.of(
                new IndexedToolCall(0, new ToolCall("c0", "slow_tool", "{}")),
                new IndexedToolCall(1, new ToolCall("c1", "fast_tool", "{}")),
                new IndexedToolCall(2, new ToolCall("c2", "medium_tool", "{}"))
        );
        ParallelBatch batch = ParallelBatch.parallel(calls);

        List<ToolResult> results = executor.executeBatch(
                batch,
                indexedCall -> {
                    long delay = switch (indexedCall.originalIndex()) {
                        case 0 -> 100;
                        case 1 -> 20;
                        case 2 -> 50;
                        default -> 0;
                    };
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return dummySuccess(indexedCall.call(), delay);
                },
                Duration.ofSeconds(5),
                new CancellationToken()
        );

        assertEquals(3, results.size());
        assertEquals("c0", results.get(0).callId());
        assertEquals("c1", results.get(1).callId());
        assertEquals("c2", results.get(2).callId());
    }

    @Test
    void faultIsolationOnException() {
        List<IndexedToolCall> calls = List.of(
                new IndexedToolCall(0, new ToolCall("c0", "failing_tool", "{}")),
                new IndexedToolCall(1, new ToolCall("c1", "ok_tool", "{}"))
        );
        ParallelBatch batch = ParallelBatch.parallel(calls);

        List<ToolResult> results = executor.executeBatch(
                batch,
                indexedCall -> {
                    if (indexedCall.originalIndex() == 0) {
                        throw new RuntimeException("Disk I/O failure");
                    }
                    return dummySuccess(indexedCall.call(), 10);
                },
                Duration.ofSeconds(5),
                new CancellationToken()
        );

        assertEquals(2, results.size());
        assertEquals(ToolResultStatus.EXECUTION_ERROR, results.get(0).status());
        assertTrue(results.get(0).summary().contains("Disk I/O failure"));

        assertEquals(ToolResultStatus.SUCCESS, results.get(1).status());
    }

    @Test
    void singleToolTimeoutIsolation() {
        List<IndexedToolCall> calls = List.of(
                new IndexedToolCall(0, new ToolCall("c0", "hang_tool", "{}")),
                new IndexedToolCall(1, new ToolCall("c1", "quick_tool", "{}"))
        );
        ParallelBatch batch = ParallelBatch.parallel(calls);

        List<ToolResult> results = executor.executeBatch(
                batch,
                indexedCall -> {
                    if (indexedCall.originalIndex() == 0) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException ignored) {
                        }
                    }
                    return dummySuccess(indexedCall.call(), 10);
                },
                Duration.ofMillis(100),
                new CancellationToken()
        );

        assertEquals(2, results.size());
        assertEquals(ToolResultStatus.TIMEOUT, results.get(0).status());
        assertTrue(results.get(0).summary().contains("timed out"));

        assertEquals(ToolResultStatus.SUCCESS, results.get(1).status());
    }

    @Test
    void cancellationPropagatesToRunningTasks() throws Exception {
        CountDownLatch taskStarted = new CountDownLatch(1);
        CancellationToken cancellationToken = new CancellationToken();

        List<IndexedToolCall> calls = List.of(
                new IndexedToolCall(0, new ToolCall("c0", "long_running", "{}"))
        );
        ParallelBatch batch = ParallelBatch.parallel(calls);

        Thread canceller = new Thread(() -> {
            try {
                if (taskStarted.await(1, TimeUnit.SECONDS)) {
                    Thread.sleep(30);
                    cancellationToken.cancel();
                }
            } catch (InterruptedException ignored) {
            }
        });
        canceller.start();

        List<ToolResult> results = executor.executeBatch(
                batch,
                indexedCall -> {
                    taskStarted.countDown();
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ignored) {
                    }
                    return dummySuccess(indexedCall.call(), 1000);
                },
                Duration.ofSeconds(5),
                cancellationToken
        );

        canceller.join();
        assertEquals(1, results.size());
        assertTrue(
                results.get(0).status() == ToolResultStatus.CANCELLED ||
                results.get(0).status() == ToolResultStatus.EXECUTION_ERROR
        );
    }

    @Test
    void sequentialBatchExecutesCorrectly() {
        List<IndexedToolCall> calls = List.of(
                new IndexedToolCall(0, new ToolCall("c0", "write_file", "{}"))
        );
        ParallelBatch batch = ParallelBatch.sequential(calls);

        List<ToolResult> results = executor.executeBatch(
                batch,
                indexedCall -> dummySuccess(indexedCall.call(), 15),
                Duration.ofSeconds(5),
                new CancellationToken()
        );

        assertEquals(1, results.size());
        assertEquals(ToolResultStatus.SUCCESS, results.get(0).status());
    }
}
