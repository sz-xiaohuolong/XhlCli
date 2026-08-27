package com.xhlcli.agent;

import com.xhlcli.model.RunStatus;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Owns the state of one run and prevents work after a terminal transition. */
public final class RunLifecycle {
    private final AtomicReference<RunStatus> status = new AtomicReference<>(RunStatus.CREATED);

    public RunStatus status() {
        return status.get();
    }

    public void transitionTo(RunStatus next) {
        Objects.requireNonNull(next, "next");
        if (next.isTerminal()) {
            throw new IllegalArgumentException("Terminal statuses must use finish");
        }

        while (true) {
            RunStatus current = status.get();
            if (current.isTerminal()) {
                throw new IllegalStateException("Run is already terminal: " + current);
            }
            if (!isAllowed(current, next)) {
                throw new IllegalStateException("Illegal run transition: " + current + " -> " + next);
            }
            if (status.compareAndSet(current, next)) {
                return;
            }
        }
    }

    public boolean finish(RunStatus terminalStatus) {
        Objects.requireNonNull(terminalStatus, "terminalStatus");
        if (!terminalStatus.isTerminal()) {
            throw new IllegalArgumentException("finish requires a terminal status");
        }

        while (true) {
            RunStatus current = status.get();
            if (current.isTerminal()) {
                return false;
            }
            if (status.compareAndSet(current, terminalStatus)) {
                return true;
            }
        }
    }

    public void requireActive() {
        RunStatus current = status.get();
        if (current.isTerminal()) {
            throw new IllegalStateException("Run is terminal: " + current);
        }
    }

    private boolean isAllowed(RunStatus current, RunStatus next) {
        return switch (current) {
            case CREATED -> next == RunStatus.THINKING;
            case THINKING -> next == RunStatus.CALLING_TOOL;
            case CALLING_TOOL -> next == RunStatus.OBSERVING;
            case OBSERVING -> next == RunStatus.THINKING;
            default -> false;
        };
    }
}
