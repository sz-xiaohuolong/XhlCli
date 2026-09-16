package com.xhlcli.team;

/**
 * Multi-Agent 系统中的专职角色定义。
 *
 * 维护 1+2+1 的角色分工：
 * - PLANNER：负责分析用户目标，拆解任务，输出步骤依赖图，禁止使用任何工具
 * - WORKER：负责执行具体步骤，唯一允许调用工具（读写文件、执行命令等）
 * - REVIEWER：负责审查执行结果的正确性与完整性，提供通过/不通过与条目化反馈
 */
public enum TeamRole {
    PLANNER("规划者", "负责分析用户任务，制定执行计划，将复杂任务拆解为可执行的子任务"),
    WORKER("执行者", "负责执行具体任务步骤，调用工具完成文件操作、命令执行等操作"),
    REVIEWER("检查者", "负责检查执行结果的质量和正确性，提供改进建议");

    private final String displayName;
    private final String description;

    TeamRole(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }
}
