package com.xhlcli.render;

import org.jline.terminal.Terminal;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * 终端环境特征探测器。
 * 负责探测 NO_COLOR、--plain 参数、终端类型与无交互 TTY 管道重定向状态。
 */
public final class TerminalEnvironment {

    private final boolean noColor;
    private final boolean plainRequested;
    private final String terminalType;
    private final boolean hasConsole;

    public TerminalEnvironment(boolean noColor, boolean plainRequested, String terminalType, boolean hasConsole) {
        this.noColor = noColor;
        this.plainRequested = plainRequested;
        this.terminalType = terminalType == null ? "" : terminalType.trim().toLowerCase(java.util.Locale.ROOT);
        this.hasConsole = hasConsole;
    }

    public static TerminalEnvironment detect(String[] args, Map<String, String> env, Terminal terminal) {
        Objects.requireNonNull(env, "env");
        String noColorVal = env.get("NO_COLOR");
        boolean noColor = noColorVal != null && !noColorVal.isEmpty();

        boolean plainRequested = args != null && Arrays.asList(args).contains("--plain");

        String termType = terminal != null ? terminal.getType() : env.getOrDefault("TERM", "dumb");
        boolean hasConsole = System.console() != null;

        return new TerminalEnvironment(noColor, plainRequested, termType, hasConsole);
    }

    public static TerminalEnvironment detectSystem(String[] args) {
        return detect(args, System.getenv(), null);
    }

    public boolean isNoColor() {
        return noColor;
    }

    public boolean isPlainRequested() {
        return plainRequested;
    }

    public String terminalType() {
        return terminalType;
    }

    public boolean hasConsole() {
        return hasConsole;
    }

    public boolean isDumbTerminal() {
        return "dumb".equalsIgnoreCase(terminalType) || terminalType.isEmpty();
    }

    /**
     * 判断当前环境是否支持 ANSI 转义序列（颜色与光标控制）。
     */
    public boolean isAnsiSupported() {
        if (noColor || plainRequested) {
            return false;
        }
        return !isDumbTerminal();
    }

    /**
     * 判断当前是否处于可交互的终端会话中。
     */
    public boolean isInteractive() {
        if (plainRequested) {
            return false;
        }
        if (isDumbTerminal()) {
            return false;
        }
        return hasConsole;
    }
}
