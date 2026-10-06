package com.xhlcli.render.terminal;

import com.xhlcli.render.TerminalStatus;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Status;

import java.util.List;
import java.util.Objects;

/**
 * 底部终端自适应状态栏。
 * 接入 JLine 4 Status 组件，依据终端列宽（80/120/160列）动态优先级裁剪状态字段，强制杜绝换行。
 */
public final class TerminalStatusBar {

    private final Terminal terminal;
    private final Status jlineStatus;

    public TerminalStatusBar(Terminal terminal) {
        this.terminal = terminal;
        this.jlineStatus = (terminal != null && !"dumb".equalsIgnoreCase(terminal.getType()))
                ? Status.getStatus(terminal, false)
                : null;
    }

    /**
     * 更新状态栏显示。
     */
    public synchronized void update(TerminalStatus status) {
        if (jlineStatus == null || terminal == null || status == null) {
            return;
        }
        int width = terminal.getWidth();
        if (width <= 0) {
            width = 80;
        }
        AttributedString line = formatAttributed(status, width);
        jlineStatus.update(List.of(line));
    }

    /**
     * 临时挂起状态栏（供 HITL 或全屏子流程使用）。
     */
    public synchronized void suspend() {
        if (jlineStatus != null) {
            jlineStatus.suspend();
        }
    }

    /**
     * 恢复状态栏显示。
     */
    public synchronized void restore() {
        if (jlineStatus != null) {
            jlineStatus.restore();
        }
    }

    /**
     * 隐藏状态栏。
     */
    public synchronized void hide() {
        if (jlineStatus != null) {
            jlineStatus.hide();
        }
    }

    /**
     * 纯文本格式化状态行（根据终端可用列宽自适应裁剪）。
     */
    public static String formatPlain(TerminalStatus status, int columns) {
        Objects.requireNonNull(status, "status");
        int targetWidth = Math.max(columns, 30);

        String p0 = String.format("[%s] [%s] %s", status.mode(), status.phase(), status.model());
        if (TerminalWidthCalculator.displayWidth(p0) >= targetWidth) {
            return TerminalWidthCalculator.truncateToWidth(p0, targetWidth, "…");
        }

        // P1: Context tokens
        String p1 = formatTokens(status.currentTokens(), status.maxTokens());

        // P2: Extensions
        String p2 = String.format("MCP:%d Skills:%d", status.activeMcpServers(), status.activeSkills());

        // P3: Workspace path
        String p3 = status.workspacePath();

        // 尝试完整组装 P0 + P1 + P2 + P3
        String full = String.format("%s | %s | %s | %s", p0, p1, p2, p3);
        if (TerminalWidthCalculator.displayWidth(full) <= targetWidth) {
            return full;
        }

        // 列宽不足以容纳全路径时，尝试压缩工作区路径为基名
        String shortPath = abbreviatePath(p3);
        String withShortPath = String.format("%s | %s | %s | %s", p0, p1, p2, shortPath);
        if (TerminalWidthCalculator.displayWidth(withShortPath) <= targetWidth) {
            return withShortPath;
        }

        // 进一步裁剪掉 P3 工作区路径
        String p012 = String.format("%s | %s | %s", p0, p1, p2);
        if (TerminalWidthCalculator.displayWidth(p012) <= targetWidth) {
            return p012;
        }

        // 进一步裁剪掉 P2 扩展信息
        String p01 = String.format("%s | %s", p0, p1);
        if (TerminalWidthCalculator.displayWidth(p01) <= targetWidth) {
            return p01;
        }

        // 极端狭窄终端仅保留 P0
        return TerminalWidthCalculator.truncateToWidth(p0, targetWidth, "…");
    }

    /**
     * 带有 ANSI 配色样式的 AttributedString 格式化。
     */
    public static AttributedString formatAttributed(TerminalStatus status, int columns) {
        String plain = formatPlain(status, columns);
        AttributedStringBuilder asb = new AttributedStringBuilder();

        // 使用淡青色与深灰底色的反显高雅现代风格
        asb.style(AttributedStyle.DEFAULT.background(AttributedStyle.BLUE).foreground(AttributedStyle.WHITE).bold());
        asb.append(String.format(" [%s] ", status.mode()));

        asb.style(AttributedStyle.DEFAULT.background(AttributedStyle.BLACK).foreground(AttributedStyle.YELLOW).bold());
        asb.append(String.format(" %s ", status.phase()));

        asb.style(AttributedStyle.DEFAULT.background(AttributedStyle.BLACK).foreground(AttributedStyle.CYAN));
        asb.append(String.format(" %s ", status.model()));

        String p1 = formatTokens(status.currentTokens(), status.maxTokens());
        asb.style(AttributedStyle.DEFAULT.background(AttributedStyle.BLACK).foreground(AttributedStyle.GREEN));
        asb.append(String.format("│ %s ", p1));

        if (plain.contains("MCP:")) {
            asb.style(AttributedStyle.DEFAULT.background(AttributedStyle.BLACK).foreground(AttributedStyle.MAGENTA));
            asb.append(String.format("│ MCP:%d Skills:%d ", status.activeMcpServers(), status.activeSkills()));
        }

        if (plain.contains(abbreviatePath(status.workspacePath())) || plain.contains(status.workspacePath())) {
            asb.style(AttributedStyle.DEFAULT.background(AttributedStyle.BLACK).foreground(AttributedStyle.WHITE));
            String pathToShow = plain.contains(status.workspacePath()) ? status.workspacePath() : abbreviatePath(status.workspacePath());
            asb.append(String.format("│ %s", pathToShow));
        }

        // 限制宽度，防止越界换行
        AttributedString fullAttributed = asb.toAttributedString();
        if (fullAttributed.columnLength() > columns && columns > 0) {
            return fullAttributed.columnSubSequence(0, columns);
        }
        return fullAttributed;
    }

    private static String formatTokens(int current, int max) {
        if (max > 0) {
            int pct = (int) Math.round(((double) current / max) * 100);
            return String.format("%s/%s (%d%%)", formatK(current), formatK(max), pct);
        }
        if (current > 0) {
            return formatK(current) + " tokens";
        }
        return "0 tokens";
    }

    private static String formatK(int tokens) {
        if (tokens >= 1000) {
            return String.format("%.1fk", tokens / 1000.0);
        }
        return String.valueOf(tokens);
    }

    private static String abbreviatePath(String path) {
        if (path == null || path.isBlank()) {
            return ".";
        }
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        if (lastSlash >= 0 && lastSlash < path.length() - 1) {
            return "…/" + path.substring(lastSlash + 1);
        }
        return path;
    }
}
