package com.xhlcli.hitl;

/**
 * 人工审批 (HITL) 交互接口。
 */
public interface HitlHandler {

    /**
     * 向用户展示审批请求并收集决策。
     *
     * @param request 待审批的工具调用信息
     * @return 用户的审批决策
     */
    ApprovalResult requestApproval(ApprovalRequest request);

    /**
     * 是否启用 HITL 审批。
     */
    boolean isEnabled();

    /**
     * 启用或禁用 HITL 审批。
     */
    void setEnabled(boolean enabled);

    /**
     * 判断某个工具在当前会话中是否已被全部放行。
     */
    default boolean isApprovedAllByTool(String toolName) {
        return false;
    }

    /**
     * 清除当前会话中所有"全部放行"的临时授权记录。
     */
    default void clearApprovedAll() {
    }
}
