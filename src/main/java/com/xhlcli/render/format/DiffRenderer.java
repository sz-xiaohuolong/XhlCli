package com.xhlcli.render.format;

import java.util.ArrayList;
import java.util.List;

/**
 * Git Diff / Unified Patch 格式化渲染引擎。
 * 提供增删行统计、彩色统一差异着色与纯文本排版。
 */
public final class DiffRenderer {

    private DiffRenderer() {}

    public record DiffStats(
            String filePath,
            int addedLines,
            int deletedLines
    ) {}

    /**
     * 解析 Diff 统计信息。
     */
    public static DiffStats analyze(String diffContent) {
        if (diffContent == null || diffContent.isBlank()) {
            return new DiffStats("unknown", 0, 0);
        }

        String path = "unknown";
        int added = 0;
        int deleted = 0;

        for (String line : diffContent.lines().toList()) {
            if (line.startsWith("+++ b/")) {
                path = line.substring("+++ b/".length()).trim();
            } else if (line.startsWith("--- a/") && "unknown".equals(path)) {
                path = line.substring("--- a/".length()).trim();
            } else if (line.startsWith("+") && !line.startsWith("+++")) {
                added++;
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                deleted++;
            }
        }

        return new DiffStats(path, added, deleted);
    }

    /**
     * 判断文本是否属于 Unified Diff 补丁内容。
     */
    public static boolean isDiff(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.contains("diff --git")
                || (text.contains("--- a/") && text.contains("+++ b/"))
                || text.contains("@@ -");
    }

    /**
     * 渲染带有 ANSI 语法高亮的 Diff 文本。
     */
    public static String renderColored(String diffContent) {
        if (diffContent == null || diffContent.isBlank()) {
            return "";
        }

        DiffStats stats = analyze(diffContent);
        StringBuilder sb = new StringBuilder();

        // 头部统计摘要卡片
        sb.append(String.format("\u001B[1;36m📄 File: %s \u001B[32m(+%d)\u001B[0m \u001B[31m(-%d)\u001B[0m\n",
                stats.filePath(), stats.addedLines(), stats.deletedLines()));

        for (String line : diffContent.lines().toList()) {
            if (line.startsWith("diff --git") || line.startsWith("index ") || line.startsWith("--- a/") || line.startsWith("+++ b/")) {
                sb.append("\u001B[2m").append(line).append("\u001B[0m\n");
            } else if (line.startsWith("@@")) {
                sb.append("\u001B[33m").append(line).append("\u001B[0m\n");
            } else if (line.startsWith("+")) {
                sb.append("\u001B[32m").append(line).append("\u001B[0m\n");
            } else if (line.startsWith("-")) {
                sb.append("\u001B[31m").append(line).append("\u001B[0m\n");
            } else {
                sb.append(line).append("\n");
            }
        }

        return sb.toString().stripTrailing();
    }

    /**
     * 渲染无 ANSI 样式的纯文本 Diff。
     */
    public static String renderPlain(String diffContent) {
        if (diffContent == null || diffContent.isBlank()) {
            return "";
        }
        DiffStats stats = analyze(diffContent);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("File: %s (+%d, -%d)\n", stats.filePath(), stats.addedLines(), stats.deletedLines()));
        sb.append(diffContent);
        return sb.toString().stripTrailing();
    }
}
