package com.xhlcli.hitl;

/**
 * 审批结果：用户对一次工具调用审批请求的最终决定。
 */
public record ApprovalResult(
        Decision decision,
        String modifiedArguments,
        String reason
) {
    public enum Decision {
        /** 批准本次操作 */
        APPROVED,
        /** 批准本次会话后续所有同类工具操作 */
        APPROVED_ALL,
        /** 拒绝执行本次操作 */
        REJECTED,
        /** 用户修改参数后执行 */
        MODIFIED,
        /** 跳过本步骤 */
        SKIPPED
    }

    public static ApprovalResult approve() {
        return new ApprovalResult(Decision.APPROVED, null, null);
    }

    public static ApprovalResult approveAll() {
        return new ApprovalResult(Decision.APPROVED_ALL, null, null);
    }

    public static ApprovalResult reject(String reason) {
        return new ApprovalResult(Decision.REJECTED, null, reason);
    }

    public static ApprovalResult modify(String modifiedArguments) {
        return new ApprovalResult(Decision.MODIFIED, modifiedArguments, null);
    }

    public static ApprovalResult skip() {
        return new ApprovalResult(Decision.SKIPPED, null, null);
    }

    public boolean isApproved() {
        return decision == Decision.APPROVED || decision == Decision.APPROVED_ALL || decision == Decision.MODIFIED;
    }

    public boolean isApprovedAll() {
        return decision == Decision.APPROVED_ALL;
    }

    public boolean isRejected() {
        return decision == Decision.REJECTED;
    }

    public boolean isSkipped() {
        return decision == Decision.SKIPPED;
    }

    public boolean isModified() {
        return decision == Decision.MODIFIED;
    }

    /**
     * 获取最终执行使用的参数。若是 MODIFIED 则返回修改后的参数，否则返回原始参数。
     */
    public String effectiveArguments(String originalArguments) {
        if (decision == Decision.MODIFIED && modifiedArguments != null && !modifiedArguments.isBlank()) {
            return modifiedArguments;
        }
        return originalArguments;
    }
}
