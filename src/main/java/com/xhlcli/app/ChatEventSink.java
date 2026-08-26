package com.xhlcli.app;

@FunctionalInterface
public interface ChatEventSink {
    void accept(ChatEvent event);
}
