package com.xhlcli.memory;

import java.util.List;
import java.util.Map;

/** 召回初始参数；阈值是可评测的基线，不代表最佳取值。 */
public record MemoryRecallSettings(int maxEntries, int tokenBudget, double minScore) {
    public MemoryRecallSettings {
        if (maxEntries < 0 || tokenBudget < 0 || !Double.isFinite(minScore) || minScore < 0 || minScore > 1) {
            throw new IllegalArgumentException("记忆条数和预算必须非负，最低相关性必须位于 0～1");
        }
    }

    public static MemoryRecallSettings defaults() { return new MemoryRecallSettings(5, 1500, 0.18); }

    public static MemoryRecallSettings fromEnvironment(Map<String, String> environment) {
        try {
            return new MemoryRecallSettings(
                    Integer.parseInt(environment.getOrDefault("XHLCLI_MEMORY_MAX_ENTRIES", "5").trim()),
                    Integer.parseInt(environment.getOrDefault("XHLCLI_MEMORY_TOKEN_BUDGET", "1500").trim()),
                    Double.parseDouble(environment.getOrDefault("XHLCLI_MEMORY_MIN_SCORE", "0.18").trim()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("记忆召回配置无效：请检查 XHLCLI_MEMORY_MAX_ENTRIES、XHLCLI_MEMORY_TOKEN_BUDGET、XHLCLI_MEMORY_MIN_SCORE");
        }
    }

    public MemoryQuery query(String input, List<String> recentUserMessages) {
        return new MemoryQuery(input, recentUserMessages, maxEntries, tokenBudget, minScore);
    }
}
