package com.xhlcli.llm;

@FunctionalInterface
public interface StreamListener {
    void onTextDelta(String delta);
}
