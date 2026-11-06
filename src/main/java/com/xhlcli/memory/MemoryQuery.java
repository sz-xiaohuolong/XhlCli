package com.xhlcli.memory;

import java.util.List;
import java.util.Objects;

/** 查询不携带路径，候选范围只能由绑定的 MemoryManager 决定。 */
public record MemoryQuery(String input, List<String> recentUserMessages, int maxEntries, int tokenBudget, double minScore) {
    public MemoryQuery {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(recentUserMessages, "recentUserMessages");
        new MemoryRecallSettings(maxEntries, tokenBudget, minScore);
        recentUserMessages = recentUserMessages.subList(Math.max(0, recentUserMessages.size() - 2), recentUserMessages.size())
                .stream().map(s -> s.substring(0, Math.min(s.length(), 512))).toList();
    }

    public MemoryQuery withTokenBudget(int tokens) {
        return new MemoryQuery(input, recentUserMessages, maxEntries, Math.max(0, tokens), minScore);
    }
}
