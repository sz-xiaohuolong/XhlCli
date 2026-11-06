package com.xhlcli.runtime.task;

import com.xhlcli.agent.ScheduledTimeoutScheduler;
import com.xhlcli.hitl.ApprovalPolicy;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.*;
import com.xhlcli.parallel.BoundedParallelExecutor;
import com.xhlcli.tool.ToolExecutor;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** 跟踪工具的真实退出；Future 返回超时不等于工具已停止。 */
final class TaskToolExecutor implements ToolExecutor, AutoCloseable {
    private final ToolExecutor delegate;
    private final CancellationToken parent;
    private final Duration timeout;
    private final ScheduledTimeoutScheduler scheduler;
    private final Set<CancellationToken> active = new HashSet<>();
    private boolean closed;
    private volatile boolean denied;

    TaskToolExecutor(ToolExecutor delegate, CancellationToken parent, Duration timeout, ScheduledTimeoutScheduler scheduler) {
        this.delegate = delegate;
        this.parent = parent;
        this.timeout = timeout;
        this.scheduler = scheduler;
    }

    @Override public ToolResult execute(ToolCall call, CancellationToken operation) {
        if (!ApprovalPolicy.isReadOnly(call.name()) || call.name().startsWith("mcp__")) {
            denied = true;
            return BoundedParallelExecutor.failureResult(call, ToolResultStatus.EXECUTION_ERROR,
                    "后台任务只读；此操作需要在前台明确审批后执行", 0);
        }
        var token = new CancellationToken();
        synchronized (this) {
            if (closed || parent.isCancelled() || operation.isCancelled()) return BoundedParallelExecutor.cancelledResult(call, "任务已停止");
            active.add(token);
        }
        Thread worker = Thread.currentThread();
        var timedOut = new AtomicBoolean();
        try (var parentRegistration = parent.onCancel(token::cancel);
             var operationRegistration = operation.onCancel(token::cancel);
             var interruptRegistration = token.onCancel(worker::interrupt);
             var deadline = scheduler.schedule(timeout, () -> { timedOut.set(true); token.cancel(); })) {
            ToolResult result = token.isCancelled() ? BoundedParallelExecutor.cancelledResult(call, "工具启动前已取消") : delegate.execute(call, token);
            if (timedOut.get()) return BoundedParallelExecutor.failureResult(call, ToolResultStatus.TIMEOUT, "后台只读工具执行超时", timeout.toMillis());
            if (token.isCancelled()) return BoundedParallelExecutor.cancelledResult(call, "工具执行已停止");
            return result;
        } finally {
            Thread.interrupted();
            synchronized (this) { active.remove(token); notifyAll(); }
        }
    }

    boolean denied() { return denied; }

    @Override public void close() {
        Set<CancellationToken> pending;
        synchronized (this) { closed = true; pending = Set.copyOf(active); }
        pending.forEach(CancellationToken::cancel);
        boolean interrupted = false;
        synchronized (this) {
            while (!active.isEmpty()) {
                try { wait(); } catch (InterruptedException e) { interrupted = true; }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
