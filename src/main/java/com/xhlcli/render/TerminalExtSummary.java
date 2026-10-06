package com.xhlcli.render;

/**
 * 终端首屏扩展组件状态摘要。
 */
public record TerminalExtSummary(
        int mcpServerCount,
        int skillCount,
        boolean browserConnected
) {
    public static TerminalExtSummary empty() {
        return new TerminalExtSummary(0, 0, false);
    }
}
