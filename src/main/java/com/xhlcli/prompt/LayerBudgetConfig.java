package com.xhlcli.prompt;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 针对各 Prompt 分层的字符预算配额配置。
 */
public final class LayerBudgetConfig {
    private final Map<PromptLayer, Integer> budgets = new EnumMap<>(PromptLayer.class);

    public LayerBudgetConfig() {
        budgets.put(PromptLayer.BASE_IDENTITY, 2000);
        budgets.put(PromptLayer.SAFETY_POLICY, 15000);
        budgets.put(PromptLayer.AGENT_MODE, 4000);
        budgets.put(PromptLayer.RUNTIME_CONTEXT, 4000);
        budgets.put(PromptLayer.PROJECT_RULES_AND_MEMORY, 8000);
        budgets.put(PromptLayer.SKILL_INDEX, 3000);
        budgets.put(PromptLayer.ACTIVE_SKILLS, 15000);
        budgets.put(PromptLayer.HANDOVER, 3000);
    }

    public LayerBudgetConfig(Map<PromptLayer, Integer> customBudgets) {
        this();
        if (customBudgets != null) {
            budgets.putAll(customBudgets);
        }
    }

    public int getBudget(PromptLayer layer) {
        Objects.requireNonNull(layer, "layer");
        return budgets.getOrDefault(layer, 4000);
    }

    public LayerBudgetConfig withBudget(PromptLayer layer, int maxChars) {
        Objects.requireNonNull(layer, "layer");
        budgets.put(layer, Math.max(100, maxChars));
        return this;
    }

    public static LayerBudgetConfig defaults() {
        return new LayerBudgetConfig();
    }
}
