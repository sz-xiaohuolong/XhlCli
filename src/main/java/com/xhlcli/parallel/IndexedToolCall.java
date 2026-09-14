package com.xhlcli.parallel;

import com.xhlcli.model.ToolCall;

import java.util.Objects;

/**
 * 带有原始调用索引的工具调用，用于并发执行后保序归并
 */
public record IndexedToolCall(int originalIndex, ToolCall call) {
    public IndexedToolCall {
        Objects.requireNonNull(call, "call");
    }
}
