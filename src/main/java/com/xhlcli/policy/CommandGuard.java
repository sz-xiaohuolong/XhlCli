package com.xhlcli.policy;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 命令快速拒绝防护：在 Shell 命令进入人工审批或执行进程前的 Fast-fail 硬策略检查。
 * 拦截 LLM 容易触发的毁灭性系统命令。
 */
public final class CommandGuard {

    private static final List<DenyRule> RULES = List.of(
            new DenyRule("禁止 sudo 提权",
                    Pattern.compile("(?i)\\bsudo\\b")),
            new DenyRule("禁止 rm -rf 删除全盘或系统/用户根目录",
                    Pattern.compile("(?i)\\brm\\b(?:\\s+-[a-z0-9]+)*\\s+-[a-z0-9]*r[a-z0-9]*f[a-z0-9]*(?:\\s+-[a-z0-9]+)*\\s+(/|~|\\$home)|" +
                            "(?i)\\brm\\b(?:\\s+-[a-z0-9]+)*\\s+-[a-z0-9]*f[a-z0-9]*(?:\\s+-[a-z0-9]+)*\\s+(/|~|\\$home)|" +
                            "(?i)\\brm\\b(?:\\s+-[a-z0-9]+)*\\s+-[a-z0-9]*r[a-z0-9]*(?:\\s+-[a-z0-9]+)*\\s+-[a-z0-9]*f[a-z0-9]*(?:\\s+-[a-z0-9]+)*\\s+(/|~|\\$home)|" +
                            "(?i)\\brm\\b(?:\\s+-[a-z0-9]+)*\\s+-[a-z0-9]*f[a-z0-9]*(?:\\s+-[a-z0-9]+)*\\s+-[a-z0-9]*r[a-z0-9]*(?:\\s+-[a-z0-9]+)*\\s+(/|~|\\$home)")),
            new DenyRule("禁止 mkfs 格式化磁盘",
                    Pattern.compile("(?i)\\bmkfs(\\.|\\b)")),
            new DenyRule("禁止 dd 写入裸设备",
                    Pattern.compile("(?i)\\bdd\\b[^\\n]*\\bof=/dev/")),
            new DenyRule("识别为 fork bomb 恶意耗尽资源命令",
                    Pattern.compile(":\\(\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*\\}\\s*;\\s*:")),
            new DenyRule("禁止 curl / wget 管道直接执行远端脚本",
                    Pattern.compile("(?i)\\b(curl|wget)\\b[^|\\n]*\\|\\s*(sh|bash|zsh|fish|ksh)\\b")),
            new DenyRule("禁止扫描 /、~ 或整个文件系统",
                    Pattern.compile("(?i)\\bfind\\s+(/|~|\\$home)")),
            new DenyRule("禁止 chmod 777 全盘",
                    Pattern.compile("(?i)\\bchmod\\s+-R\\s+777\\s+(/|~)")),
            new DenyRule("禁止关机或重启系统命令",
                    Pattern.compile("(?i)\\b(shutdown|reboot|halt|poweroff)\\b"))
    );

    private CommandGuard() {
    }

    /**
     * 校验命令是否安全。
     *
     * @param command 待检查的 Shell 命令
     * @return null 表示放行；非 null 字符串为策略硬性拒绝原因
     */
    public static String check(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        String normalized = command.replaceAll("\\s+", " ").trim();

        for (DenyRule rule : RULES) {
            if (rule.pattern().matcher(normalized).find()) {
                return rule.reason();
            }
        }
        return null;
    }

    /**
     * 若命令命中硬策略黑名单，则抛出 PolicyException。
     */
    public static void validateSafe(String command) {
        String reason = check(command);
        if (reason != null) {
            throw new PolicyException("策略拒绝执行命令: " + reason);
        }
    }

    private record DenyRule(String reason, Pattern pattern) {
    }
}
