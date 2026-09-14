package com.xhlcli.parallel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.ToolCall;
import com.xhlcli.model.ToolResult;
import com.xhlcli.model.ToolResultStatus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 具有有界并发度控制的工具执行器。
 * 负责批次调度、并发执行、超时控制、取消传播与结果保序归并。
 */
public final class BoundedParallelExecutor implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ExecutorService executorService;
    private final boolean ownsExecutor;
    private final int maxConcurrency;

    public BoundedParallelExecutor(int maxConcurrency) {
        if (maxConcurrency < 1 || maxConcurrency > 16) {
            throw new IllegalArgumentException("maxConcurrency must be between 1 and 16");
        }
        this.maxConcurrency = maxConcurrency;
        this.ownsExecutor = true;
        AtomicInteger counter = new AtomicInteger(1);
        this.executorService = Executors.newFixedThreadPool(maxConcurrency, r -> {
            Thread thread = new Thread(r, "bounded-parallel-worker-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        });
    }

    public BoundedParallelExecutor(ExecutorService executorService, int maxConcurrency) {
        this.executorService = Objects.requireNonNull(executorService, "executorService");
        this.maxConcurrency = Math.max(1, maxConcurrency);
        this.ownsExecutor = false;
    }

    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    /**
     * 执行一个批次的工具调用。
     * 如果批次不是并行的或者只有1个任务，则在当前线程调度并受超时管控；
     * 如果是并行批次，则提交至线程池受控并发执行。
     *
     * @param batch             工具调用批次
     * @param taskHandler       工具单次调用执行逻辑（接收 IndexedToolCall，返回 ToolResult）
     * @param toolTimeout       单工具执行超时时长
     * @param cancellationToken 取消令牌
     * @return 保序归并后的结果列表（与 batch.calls() 的顺序严格一致）
     */
    public List<ToolResult> executeBatch(
            ParallelBatch batch,
            Function<IndexedToolCall, ToolResult> taskHandler,
            Duration toolTimeout,
            CancellationToken cancellationToken) {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(taskHandler, "taskHandler");
        Objects.requireNonNull(toolTimeout, "toolTimeout");

        List<IndexedToolCall> calls = batch.calls();
        if (calls.isEmpty()) {
            return Collections.emptyList();
        }

        if (cancellationToken != null && cancellationToken.isCancelled()) {
            List<ToolResult> cancelledResults = new ArrayList<>(calls.size());
            for (IndexedToolCall call : calls) {
                cancelledResults.add(cancelledResult(call.call(), "Run was cancelled before tool execution"));
            }
            return cancelledResults;
        }

        // 串行批次或单任务
        if (!batch.isParallel() || calls.size() <= 1) {
            List<ToolResult> results = new ArrayList<>(calls.size());
            for (IndexedToolCall call : calls) {
                if (cancellationToken != null && cancellationToken.isCancelled()) {
                    results.add(cancelledResult(call.call(), "Execution cancelled"));
                    continue;
                }
                long start = System.currentTimeMillis();
                try {
                    ToolResult res = executeWithTimeout(call, taskHandler, toolTimeout, cancellationToken);
                    results.add(res != null ? res : failureResult(call.call(), ToolResultStatus.EXECUTION_ERROR, "Tool returned null result", 0));
                } catch (Exception e) {
                    long elapsed = Math.max(0, System.currentTimeMillis() - start);
                    results.add(failureResult(call.call(), ToolResultStatus.EXECUTION_ERROR,
                            "Tool execution failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), elapsed));
                }
            }
            return results;
        }

        // 并行批次：并发提交到线程池
        int count = calls.size();
        @SuppressWarnings("unchecked")
        CompletableFuture<ToolResult>[] futures = new CompletableFuture[count];
        long timeoutMs = Math.max(1, toolTimeout.toMillis());

        for (int i = 0; i < count; i++) {
            final IndexedToolCall indexedCall = calls.get(i);
            final ToolCall call = indexedCall.call();
            final long startTime = System.currentTimeMillis();

            CompletableFuture<ToolResult> cf = CompletableFuture.supplyAsync(() -> {
                if (cancellationToken != null && cancellationToken.isCancelled()) {
                    return cancelledResult(call, "Cancelled before execution");
                }
                try {
                    return taskHandler.apply(indexedCall);
                } catch (Throwable t) {
                    long elapsed = Math.max(0, System.currentTimeMillis() - startTime);
                    return failureResult(call, ToolResultStatus.EXECUTION_ERROR,
                            "Execution exception: " + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()), elapsed);
                }
            }, executorService)
            .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .exceptionally(ex -> {
                Throwable cause = ex instanceof CompletionException ? ex.getCause() : ex;
                long elapsed = Math.max(0, System.currentTimeMillis() - startTime);
                if (cause instanceof TimeoutException) {
                    return failureResult(call, ToolResultStatus.TIMEOUT,
                            "Tool execution timed out after " + toolTimeout.toSeconds() + "s", elapsed);
                }
                if (cause instanceof CancellationException) {
                    return cancelledResult(call, "Tool execution was cancelled");
                }
                return failureResult(call, ToolResultStatus.EXECUTION_ERROR,
                        "Execution exception: " + cause.getMessage(), elapsed);
            });

            futures[i] = cf;
        }

        // 注册协作式取消监听
        CancellationToken.Registration cancelRegistration = null;
        if (cancellationToken != null) {
            cancelRegistration = cancellationToken.onCancel(() -> {
                for (CompletableFuture<ToolResult> f : futures) {
                    if (f != null && !f.isDone()) {
                        f.cancel(true);
                    }
                }
            });
        }

        try {
            CompletableFuture.allOf(futures).join();
        } catch (Throwable ignored) {
            // allOf.join() 可能因为 cancel 抛出异常，各 future 的错误已在 exceptionally 捕获
        } finally {
            if (cancelRegistration != null) {
                cancelRegistration.close();
            }
        }

        // 严格保序归并（futures[i] 对应 calls.get(i)）
        List<ToolResult> results = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            try {
                ToolResult res = futures[i].join();
                results.add(res != null ? res : failureResult(calls.get(i).call(), ToolResultStatus.EXECUTION_ERROR, "Tool returned null result", 0));
            } catch (Exception e) {
                results.add(failureResult(calls.get(i).call(), ToolResultStatus.EXECUTION_ERROR, "Failed to get tool result: " + e.getMessage(), 0));
            }
        }

        return results;
    }

    private ToolResult executeWithTimeout(
            IndexedToolCall call,
            Function<IndexedToolCall, ToolResult> taskHandler,
            Duration timeout,
            CancellationToken cancellationToken) {
        long start = System.currentTimeMillis();
        long timeoutMs = Math.max(1, timeout.toMillis());
        CompletableFuture<ToolResult> cf = CompletableFuture.supplyAsync(() -> taskHandler.apply(call), executorService)
                .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    Throwable cause = ex instanceof CompletionException ? ex.getCause() : ex;
                    long elapsed = Math.max(0, System.currentTimeMillis() - start);
                    if (cause instanceof TimeoutException) {
                        return failureResult(call.call(), ToolResultStatus.TIMEOUT,
                                "Tool execution timed out after " + timeout.toSeconds() + "s", elapsed);
                    }
                    if (cause instanceof CancellationException) {
                        return cancelledResult(call.call(), "Execution cancelled");
                    }
                    return failureResult(call.call(), ToolResultStatus.EXECUTION_ERROR,
                            "Execution failed: " + cause.getMessage(), elapsed);
                });

        CancellationToken.Registration reg = null;
        if (cancellationToken != null) {
            reg = cancellationToken.onCancel(() -> cf.cancel(true));
        }
        try {
            return cf.join();
        } finally {
            if (reg != null) {
                reg.close();
            }
        }
    }

    public static ToolResult failureResult(ToolCall call, ToolResultStatus status, String summary, long elapsedMillis) {
        return new ToolResult(
                call.id(),
                call.name(),
                status,
                summary,
                MAPPER.createObjectNode(),
                elapsedMillis,
                false,
                0,
                "");
    }

    public static ToolResult cancelledResult(ToolCall call, String summary) {
        return failureResult(call, ToolResultStatus.CANCELLED, summary, 0);
    }

    @Override
    public void close() {
        if (ownsExecutor && !executorService.isShutdown()) {
            executorService.shutdownNow();
        }
    }
}
