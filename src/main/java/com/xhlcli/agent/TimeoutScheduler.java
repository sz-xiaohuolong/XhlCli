package com.xhlcli.agent;

import java.time.Duration;

public interface TimeoutScheduler extends AutoCloseable {
    Registration schedule(Duration delay, Runnable action);

    @Override
    void close();

    interface Registration extends AutoCloseable {
        @Override
        void close();
    }
}
