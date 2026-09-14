package com.xhlcli.parallel;

import com.xhlcli.model.ToolCall;
import com.xhlcli.model.ToolDefinition;
import com.xhlcli.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 批次切分调度器：将模型一次返回的多个工具调用划分为连续的并行批次与串行批次
 */
public final class ParallelBatchScheduler {

    private ParallelBatchScheduler() {
    }

    /**
     * 将工具调用列表划分为可并行批次与串行批次
     *
     * @param calls          模型返回的工具调用列表
     * @param registry       工具注册表
     * @param maxConcurrency 最大并行度
     * @return 划分后的批次列表
     */
    public static List<ParallelBatch> schedule(List<ToolCall> calls, ToolRegistry registry, int maxConcurrency) {
        return schedule(calls, registry != null ? registry.definitions() : null, maxConcurrency);
    }

    /**
     * 将工具调用列表划分为可并行批次与串行批次（基于工具定义列表）
     *
     * @param calls          模型返回的工具调用列表
     * @param definitions    工具定义列表
     * @param maxConcurrency 最大并行度
     * @return 划分后的批次列表
     */
    public static List<ParallelBatch> schedule(List<ToolCall> calls, List<ToolDefinition> definitions, int maxConcurrency) {
        if (calls == null || calls.isEmpty()) {
            return Collections.emptyList();
        }

        int concurrencyLimit = Math.max(1, maxConcurrency);
        List<ParallelBatch> batches = new ArrayList<>();

        List<IndexedToolCall> currentParallelCalls = new ArrayList<>();
        List<ResourceAccess> currentParallelAccesses = new ArrayList<>();

        for (int i = 0; i < calls.size(); i++) {
            ToolCall call = calls.get(i);
            IndexedToolCall indexedCall = new IndexedToolCall(i, call);

            boolean eligible = ParallelEligibilityDecider.isEligible(call, definitions);
            if (!eligible) {
                // 遇到不可并行的工具：先封口前面累积的并行批次
                if (!currentParallelCalls.isEmpty()) {
                    batches.add(ParallelBatch.parallel(currentParallelCalls));
                    currentParallelCalls.clear();
                    currentParallelAccesses.clear();
                }
                // 当前工具作为单独的串行批次
                batches.add(ParallelBatch.sequential(indexedCall));
            } else {
                ResourceAccess access = ParallelEligibilityDecider.extractAccess(call, definitions);
                boolean hasConflict = false;
                for (ResourceAccess existing : currentParallelAccesses) {
                    if (existing.conflictsWith(access)) {
                        hasConflict = true;
                        break;
                    }
                }

                // 若达到并发上限或存在资源冲突，则封口当前并行批次并开启新批次
                if (currentParallelCalls.size() >= concurrencyLimit || hasConflict) {
                    batches.add(ParallelBatch.parallel(currentParallelCalls));
                    currentParallelCalls = new ArrayList<>();
                    currentParallelAccesses = new ArrayList<>();
                }

                currentParallelCalls.add(indexedCall);
                currentParallelAccesses.add(access);
            }
        }

        // 封口残留的并行批次
        if (!currentParallelCalls.isEmpty()) {
            batches.add(ParallelBatch.parallel(currentParallelCalls));
        }

        return batches;
    }
}
