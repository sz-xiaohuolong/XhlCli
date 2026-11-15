package com.xhlcli.context;

/** 最近一次优化尝试的元数据；不保存正文，也不代表模型已收到请求。 */
public record ContextOptimizationReport(Status status, int tokensBefore, int tokensAfter,
                                        int changedMessages, long elapsedMillis, String reason) {
    public enum Status { DISABLED, SKIPPED, COMPRESSED, UNCHANGED, FALLBACK, CANCELED }

    public static String describe(boolean enabled, ContextOptimizationReport report) {
        String heading = "Headroom：" + (enabled ? "已开启" : "已关闭");
        if (report == null || report.status() == null) return heading + "\n最近一次优化尝试：尚无优化记录。";
        String state = switch (report.status()) {
            case DISABLED -> "已关闭";
            case SKIPPED -> "已跳过";
            case COMPRESSED -> "已压缩";
            case UNCHANGED -> "无变化";
            case FALLBACK -> "已回退原文";
            case CANCELED -> "已取消";
        };
        String cause = switch (report.reason() == null ? "" : report.reason()) {
            case "DISABLED" -> "未启用";
            case "NO_ELIGIBLE_CONTENT" -> "没有符合条件的工具正文";
            case "APPLIED" -> "采用更短的正文";
            case "NO_SAVINGS" -> "没有减少估算用量";
            case "REQUEST_LIMIT" -> "请求超过体积限制";
            case "RESPONSE_LIMIT" -> "响应超过体积限制";
            case "INVALID_RESPONSE" -> "响应格式或消息结构不合法";
            case "HTTP_ERROR" -> "服务返回错误状态";
            case "TIMEOUT" -> "压缩请求超时";
            case "NETWORK_ERROR" -> "无法连接压缩服务";
            case "CANCELED" -> "运行已停止";
            default -> "原因不可用";
        };
        return heading + "\n最近一次优化尝试：" + state + "（" + cause + "）"
                + "\n消息 Token 估算：" + report.tokensBefore() + " → " + report.tokensAfter()
                + "；压缩正文 " + report.changedMessages() + " 条；耗时 " + report.elapsedMillis() + " ms"
                + "\n以上是加入记忆前的消息估算，不含工具定义；不代表账单，也不证明模型已收到请求。";
    }
}
