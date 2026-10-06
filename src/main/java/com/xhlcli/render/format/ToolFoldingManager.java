package com.xhlcli.render.format;

import java.util.List;

/**
 * 工具输出折叠管理器。
 * 针对长工具结果进行折叠与摘要保护，短工具结果直接透出。
 */
public final class ToolFoldingManager {

    public static final int DEFAULT_MAX_LINES = 6;
    public static final int DEFAULT_MAX_CHARS = 300;

    private ToolFoldingManager() {}

    public record FoldedResult(
            boolean folded,
            String displayContent,
            int totalLines,
            int totalChars
    ) {}

    /**
     * 检测并折叠过长的工具结果文本。
     */
    public static FoldedResult process(String rawOutput, int maxLines, int maxChars) {
        if (rawOutput == null || rawOutput.isEmpty()) {
            return new FoldedResult(false, "", 0, 0);
        }

        List<String> lines = rawOutput.lines().toList();
        int totalLines = lines.size();
        int totalChars = rawOutput.length();

        if (totalLines <= maxLines && totalChars <= maxChars) {
            return new FoldedResult(false, rawOutput, totalLines, totalChars);
        }

        // 超出阈值，提取前 3 行预览并折叠后续行
        int previewCount = Math.min(3, totalLines);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < previewCount; i++) {
            sb.append(lines.get(i)).append("\n");
        }
        int omittedLines = totalLines - previewCount;
        sb.append(String.format("... [%d lines (%d chars) omitted]", omittedLines, totalChars));

        return new FoldedResult(true, sb.toString(), totalLines, totalChars);
    }

    public static FoldedResult process(String rawOutput) {
        return process(rawOutput, DEFAULT_MAX_LINES, DEFAULT_MAX_CHARS);
    }
}
