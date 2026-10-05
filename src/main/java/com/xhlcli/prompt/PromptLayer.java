package com.xhlcli.prompt;

/**
 * Prompt 分层枚举，定义系统提示词组装的 8 层确定性逻辑顺序与不变性约束。
 */
public enum PromptLayer {
    /** 1. 基础身份与语言 */
    BASE_IDENTITY(1, "Base Identity", false),

    /** 2. 工具与安全规则（核心安全底线，绝对不可被覆盖或取消） */
    SAFETY_POLICY(2, "Safety Policy", true),

    /** 3. 运行模式（ReAct / Plan-and-Execute / Team 专职） */
    AGENT_MODE(3, "Agent Mode", false),

    /** 4. 运行时环境上下文（时间、模型能力、工作区路径） */
    RUNTIME_CONTEXT(4, "Runtime Context", false),

    /** 5. 项目规则与检索记忆（项目规约、全局与项目长期事实） */
    PROJECT_RULES_AND_MEMORY(5, "Project Rules & Memory", false),

    /** 6. Skill 紧凑索引（按需暴露已启用 Skill 的名称和简短描述） */
    SKILL_INDEX(6, "Skill Index", false),

    /** 7. 当前 Run 按需激活的 Skill 正文 */
    ACTIVE_SKILLS(7, "Active Skills", false),

    /** 8. 上下文管理与交接要求（窗口感知、交接与精简规范） */
    HANDOVER(8, "Handover Guidelines", false);

    private final int order;
    private final String displayName;
    private final boolean immutable;

    PromptLayer(int order, String displayName, boolean immutable) {
        this.order = order;
        this.displayName = displayName;
        this.immutable = immutable;
    }

    public int order() {
        return order;
    }

    public String displayName() {
        return displayName;
    }

    public boolean isImmutable() {
        return immutable;
    }
}
