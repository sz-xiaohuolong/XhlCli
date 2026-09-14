package com.xhlcli.parallel;

import java.util.List;
import java.util.Objects;

/**
 * 表示划分出的一组工具调用批次，声明是否支持并行执行
 */
public record ParallelBatch(boolean isParallel, List<IndexedToolCall> calls) {
    public ParallelBatch {
        calls = List.copyOf(Objects.requireNonNull(calls, "calls"));
    }

    public static ParallelBatch parallel(List<IndexedToolCall> calls) {
        return new ParallelBatch(true, calls);
    }

    public static ParallelBatch sequential(IndexedToolCall call) {
        return new ParallelBatch(false, List.of(call));
    }

    public static ParallelBatch sequential(List<IndexedToolCall> calls) {
        return new ParallelBatch(false, calls);
    }

    public int size() {
        return calls.size();
    }
}
