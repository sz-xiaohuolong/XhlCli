package com.xhlcli.hitl;

/**
 * 工具调用风险等级。
 */
public enum RiskLevel {
    /** 只读操作，无副作用，无需人工确认 */
    READ_ONLY("🟢 安全", "安全的只读操作"),

    /** 中风险操作（如写入、修改文件） */
    MEDIUM_RISK("🟡 中危", "将写入或修改项目文件"),

    /** 高风险操作（如执行 Shell 命令、删除操作） */
    HIGH_RISK("🔴 高危", "将在系统上执行命令或执行潜在破坏性操作"),

    /** 系统禁止操作，不可被用户批准 */
    FORBIDDEN("⛔ 禁止", "系统硬策略禁止的操作");

    private final String label;
    private final String defaultDescription;

    RiskLevel(String label, String defaultDescription) {
        this.label = label;
        this.defaultDescription = defaultDescription;
    }

    public String label() {
        return label;
    }

    public String defaultDescription() {
        return defaultDescription;
    }
}
