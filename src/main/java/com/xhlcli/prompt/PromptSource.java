package com.xhlcli.prompt;

/**
 * Prompt 块与 Skill 的来源层级与覆盖优先级。
 */
public enum PromptSource {
    /** 内置默认（最低优先级） */
    BUILTIN(0, "内置 (Builtin)"),

    /** 用户全局（~/.xhlcli/，中等优先级） */
    USER(1, "用户级 (User)"),

    /** 项目级（.xhlcli/，最高优先级） */
    PROJECT(2, "项目级 (Project)");

    private final int priority;
    private final String description;

    PromptSource(int priority, String description) {
        this.priority = priority;
        this.description = description;
    }

    public int priority() {
        return priority;
    }

    public String description() {
        return description;
    }

    public boolean canOverride(PromptSource other) {
        if (other == null) {
            return true;
        }
        return this.priority >= other.priority;
    }
}
