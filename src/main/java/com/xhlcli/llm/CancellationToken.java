package com.xhlcli.llm;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Set<Callback> callbacks = ConcurrentHashMap.newKeySet();

    public boolean isCancelled() {
        return cancelled.get();
    }

    public Registration onCancel(Runnable action) {
        Callback callback = new Callback(Objects.requireNonNull(action, "action"));
        if (cancelled.get()) {
            callback.invoke();
            return callback;
        }

        callbacks.add(callback);
        if (cancelled.get() && callbacks.remove(callback)) {
            callback.invoke();
        }
        return callback;
    }

    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        for (Callback callback : callbacks) {
            if (callbacks.remove(callback)) {
                callback.invoke();
            }
        }
    }

    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    private final class Callback implements Registration {
        private final Runnable action;
        private final AtomicBoolean invoked = new AtomicBoolean();

        private Callback(Runnable action) {
            this.action = action;
        }

        private void invoke() {
            if (!invoked.compareAndSet(false, true)) {
                return;
            }
            try {
                action.run();
            } catch (RuntimeException ignored) {
                // Cancellation must remain best-effort and notify every registered callback.
            }
        }

        @Override
        public void close() {
            callbacks.remove(this);
        }
    }
}
