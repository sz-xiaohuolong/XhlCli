package com.xhlcli.hitl;

import java.util.Set;

/**
 * 危险操作识别与审批策略。
 * 
 * 原则：
 * 1. 只读操作（read_file, list_dir, glob_files, grep_code, git_diff, 演示工具）自动放行，无需打断；
 * 2. 写入与修改操作（write_file, apply_patch）属于中危，需要审批；
 * 3. 命令执行（execute_command）属于高危，需要审批；
 * 4. 默认安全保护（Fail-Safe）：任何未明确声明为只读的未知工具默认按高危处理，必须经过审批。
 */
public final class ApprovalPolicy {

    private static final Set<String> READ_ONLY_TOOLS = Set.of(
            "read_file",
            "list_dir",
            "glob_files",
            "grep_code",
            "git_diff",
            "echo_text",
            "current_time",
            "search_code",
            "web_search",
            "web_fetch",
            "browser_status"
    );

    private static final Set<String> MEDIUM_RISK_TOOLS = Set.of(
            "write_file",
            "apply_patch",
            "browser_connect",
            "browser_disconnect"
    );

    private static final Set<String> HIGH_RISK_TOOLS = Set.of(
            "execute_command"
    );

    private static final java.util.Set<String> TRUSTED_MCP_SERVERS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.Set<String> TRUSTED_MCP_TOOLS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private ApprovalPolicy() {
    }

    public static void registerTrustedMcpServer(String serverName) {
        if (serverName != null && !serverName.isBlank()) {
            TRUSTED_MCP_SERVERS.add(serverName.toLowerCase());
        }
    }

    public static void registerTrustedMcpTool(String toolName) {
        if (toolName != null && !toolName.isBlank()) {
            TRUSTED_MCP_TOOLS.add(toolName);
        }
    }

    public static void clearTrustedMcp() {
        TRUSTED_MCP_SERVERS.clear();
        TRUSTED_MCP_TOOLS.clear();
    }

    public static boolean isTrustedMcp(String toolName) {
        if (toolName == null || !toolName.startsWith("mcp__")) {
            return false;
        }
        if (TRUSTED_MCP_TOOLS.contains(toolName)) {
            return true;
        }
        String[] parts = toolName.split("__");
        if (parts.length >= 2) {
            String server = parts[1].toLowerCase();
            return TRUSTED_MCP_SERVERS.contains(server);
        }
        return false;
    }

    /**
     * 判断该工具调用是否需要人工审批。
     */
    public static boolean requiresApproval(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return true;
        }
        if (READ_ONLY_TOOLS.contains(toolName) || isTrustedMcp(toolName)) {
            return false;
        }
        return true;
    }

    /**
     * 获取工具的风险等级。
     */
    public static RiskLevel getRiskLevel(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return RiskLevel.HIGH_RISK;
        }
        if (READ_ONLY_TOOLS.contains(toolName) || isTrustedMcp(toolName)) {
            return RiskLevel.READ_ONLY;
        }
        if (MEDIUM_RISK_TOOLS.contains(toolName)) {
            return RiskLevel.MEDIUM_RISK;
        }
        if (HIGH_RISK_TOOLS.contains(toolName)) {
            return RiskLevel.HIGH_RISK;
        }
        // MCP 工具默认划分为中危（FR-12-05）
        if (toolName.startsWith("mcp__")) {
            return RiskLevel.MEDIUM_RISK;
        }
        // 未知工具默认按高风险处理
        return RiskLevel.HIGH_RISK;
    }

    /**
     * 获取视觉化危险等级标签。
     */
    public static String getDangerLevel(String toolName) {
        return getRiskLevel(toolName).label();
    }

    /**
     * 获取工具风险说明。
     */
    public static String getRiskDescription(String toolName) {
        if (toolName == null) return "未知工具调用";
        return switch (toolName) {
            case "execute_command" -> "将在系统上执行 Shell 命令，可能修改文件、安装软件或产生外部副作用";
            case "write_file" -> "将写入或覆盖文件内容，原有内容可能被替换";
            case "apply_patch" -> "将对文件内容进行局部补丁替换";
            case "read_file" -> "读取指定文件内容（只读）";
            case "list_dir" -> "列出目录内容（只读）";
            case "glob_files" -> "按模式查找文件路径（只读）";
            case "grep_code" -> "按文本或正则检索代码（只读）";
            case "git_diff" -> "查看工作区未暂存差异（只读）";
            default -> isReadOnly(toolName) ? "安全的只读操作" : "未注册的新增工具，默认按高风险操作审批";
        };
    }

    public static boolean isReadOnly(String toolName) {
        return toolName != null && READ_ONLY_TOOLS.contains(toolName);
    }
}
