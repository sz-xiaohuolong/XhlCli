package com.xhlcli.llm;

import java.util.Map;

@FunctionalInterface
public interface DiagnosticSink {
    DiagnosticSink NO_OP = (event, metadata) -> {};

    void debug(String event, Map<String, String> metadata);
}
