package com.xhlcli.agent;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Schedules timeout callbacks without allowing work after the scheduler is closed. */
public final class ScheduledTimeoutScheduler implements TimeoutScheduler {
    private final ScheduledThreadPoolExecutor executor;
    private final AtomicBoolean closed = new AtomicBoolean();

    public ScheduledTimeoutScheduler() {
        this(new ScheduledThreadPoolExecutor(1, new TimeoutThreadFactory()));
    }

    ScheduledTimeoutScheduler(ScheduledThreadPoolExecutor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.executor.setRemoveOnCancelPolicy(true);
    }

    @Override
    public Registration schedule(Duration delay, Runnable action) {
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(action, "action");
        if (delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative");
        }
        if (closed.get()) {
            throw new IllegalStateException("Timeout scheduler is closed");
        }

        try {
            ScheduledFuture<?> future = executor.schedule(action, delay.toNanos(), TimeUnit.NANOSECONDS);
            return () -> future.cancel(false);
        } catch (RejectedExecutionException failure) {
            throw new IllegalStateException("Timeout scheduler is closed", failure);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdownNow();
        }
    }

    private static final class TimeoutThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "xhlcli-timeout-scheduler");
            thread.setDaemon(true);
            return thread;
        }
    }
}
