package com.xhlcli.model;

@FunctionalInterface
public interface RunEventSink {
    void accept(RunEvent event);
}
