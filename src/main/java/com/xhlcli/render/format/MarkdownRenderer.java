package com.xhlcli.render.format;

import com.xhlcli.render.terminal.TerminalWidthCalculator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 终端轻量 Markdown 格式化引擎。
 * 格式化标题、列表项、带边框代码块与自适应列宽对齐表格。
 */
public final class MarkdownRenderer {

    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*([^*]+)\\*\\*");
    private static final Pattern CODE_INLINE_PATTERN = Pattern.compile("`([^`]+)`");
    private static final Pattern LIST_ITEM_PATTERN = Pattern.compile("^(\\s*)[-*]\\s+(.*)$");

    private MarkdownRenderer() {}

    public static String render(String markdown, int columns, boolean ansi) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }

        int targetWidth = Math.max(columns > 0 ? columns : 80, 40);
        List<String> rawLines = markdown.lines().toList();
        List<String> result = new ArrayList<>();

        boolean inCodeBlock = false;
        String codeBlockLang = "";
        List<String> tableBuffer = new ArrayList<>();

        for (String line : rawLines) {
            String trimmed = line.trim();

            // 检测代码块进入/退出
            if (trimmed.startsWith("```")) {
                if (!inCodeBlock) {
                    flushTable(tableBuffer, result, targetWidth, ansi);
                    inCodeBlock = true;
                    codeBlockLang = trimmed.substring(3).trim();
                    result.add(renderCodeBlockTop(codeBlockLang, targetWidth, ansi));
                } else {
                    inCodeBlock = false;
                    result.add(renderCodeBlockBottom(targetWidth, ansi));
                }
                continue;
            }

            if (inCodeBlock) {
                // 代码块正文：缩进 2 空格
                String codeLine = "  " + line;
                result.add(ansi ? "\u001B[33m" + codeLine + "\u001B[0m" : codeLine);
                continue;
            }

            // 表格行缓冲
            if (trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.length() > 2) {
                tableBuffer.add(trimmed);
                continue;
            } else {
                flushTable(tableBuffer, result, targetWidth, ansi);
            }

            // 标题渲染
            if (trimmed.startsWith("#")) {
                result.add(renderHeader(trimmed, ansi));
                continue;
            }

            // 列表项渲染
            Matcher listMatcher = LIST_ITEM_PATTERN.matcher(line);
            if (listMatcher.matches()) {
                String indent = listMatcher.group(1);
                String itemContent = renderInline(listMatcher.group(2), ansi);
                result.add(indent + "  • " + itemContent);
                continue;
            }

            // 普通正文渲染行内元素
            result.add(renderInline(line, ansi));
        }

        flushTable(tableBuffer, result, targetWidth, ansi);
        return String.join("\n", result);
    }

    public static String renderColored(String markdown, int columns) {
        return render(markdown, columns, true);
    }

    public static String renderPlain(String markdown, int columns) {
        return render(markdown, columns, false);
    }

    private static String renderHeader(String trimmed, boolean ansi) {
        if (!ansi) {
            return trimmed;
        }
        if (trimmed.startsWith("### ")) {
            return "\u001B[1;36m" + trimmed + "\u001B[0m";
        }
        if (trimmed.startsWith("## ")) {
            return "\u001B[1;36m" + trimmed + "\u001B[0m";
        }
        if (trimmed.startsWith("# ")) {
            return "\u001B[1;35m" + trimmed + "\u001B[0m";
        }
        return "\u001B[1m" + trimmed + "\u001B[0m";
    }

    private static String renderCodeBlockTop(String lang, int width, boolean ansi) {
        String langLabel = lang.isBlank() ? "" : " " + lang + " ";
        int borderLen = Math.max(width - 5 - langLabel.length(), 5);
        String border = "┌───" + langLabel + "─".repeat(borderLen);
        return ansi ? "\u001B[2m" + border + "\u001B[0m" : border;
    }

    private static String renderCodeBlockBottom(int width, boolean ansi) {
        String border = "└" + "─".repeat(Math.max(width - 1, 10));
        return ansi ? "\u001B[2m" + border + "\u001B[0m" : border;
    }

    private static String renderInline(String text, boolean ansi) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (!ansi) {
            return text;
        }
        String res = BOLD_PATTERN.matcher(text).replaceAll("\u001B[1m$1\u001B[22m");
        res = CODE_INLINE_PATTERN.matcher(res).replaceAll("\u001B[33m$1\u001B[39m");
        return res;
    }

    private static void flushTable(List<String> tableBuffer, List<String> result, int maxWidth, boolean ansi) {
        if (tableBuffer.isEmpty()) {
            return;
        }
        if (tableBuffer.size() < 2) {
            result.addAll(tableBuffer);
            tableBuffer.clear();
            return;
        }

        // 解析单元格行
        List<List<String>> rows = new ArrayList<>();
        int colCount = 0;
        int sepIndex = -1;

        for (int i = 0; i < tableBuffer.size(); i++) {
            String rawRow = tableBuffer.get(i);
            String[] cells = Arrays.stream(rawRow.substring(1, rawRow.length() - 1).split("\\|", -1))
                    .map(String::trim)
                    .toArray(String[]::new);

            if (isSeparatorRow(cells)) {
                sepIndex = i;
                continue;
            }

            colCount = Math.max(colCount, cells.length);
            rows.add(Arrays.asList(cells));
        }

        if (rows.isEmpty() || colCount == 0) {
            result.addAll(tableBuffer);
            tableBuffer.clear();
            return;
        }

        // 计算每列最大宽度
        int[] colWidths = new int[colCount];
        for (List<String> row : rows) {
            for (int c = 0; c < row.size(); c++) {
                colWidths[c] = Math.max(colWidths[c], TerminalWidthCalculator.displayWidth(row.get(c)));
            }
        }

        // 保证最小列宽 3
        for (int c = 0; c < colCount; c++) {
            colWidths[c] = Math.max(colWidths[c], 3);
        }

        // 计算总表格宽度: 1 (边框) + sum(colWidths[c] + 3 (空格与竖线))
        int totalTableWidth = 1;
        for (int w : colWidths) {
            totalTableWidth += w + 3;
        }

        // 若总宽度超出可用终端列宽，等比例按比例缩小各列
        if (totalTableWidth > maxWidth) {
            int available = maxWidth - (1 + colCount * 3);
            if (available > colCount * 3) {
                int sumMax = 0;
                for (int w : colWidths) sumMax += w;
                for (int c = 0; c < colCount; c++) {
                    colWidths[c] = Math.max(3, (int) Math.floor((double) colWidths[c] / sumMax * available));
                }
            }
        }

        // 渲染表格边框与各行
        result.add(formatTableBorder("┌", "┬", "┐", colWidths, ansi));
        for (int r = 0; r < rows.size(); r++) {
            result.add(formatTableRow(rows.get(r), colWidths, ansi));
            if (r == 0 && sepIndex >= 0) {
                result.add(formatTableBorder("├", "┼", "┤", colWidths, ansi));
            }
        }
        result.add(formatTableBorder("└", "┴", "┘", colWidths, ansi));

        tableBuffer.clear();
    }

    private static boolean isSeparatorRow(String[] cells) {
        for (String cell : cells) {
            String c = cell.replace("-", "").replace(":", "").trim();
            if (!c.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static String formatTableBorder(String left, String mid, String right, int[] widths, boolean ansi) {
        StringBuilder sb = new StringBuilder();
        sb.append(left);
        for (int c = 0; c < widths.length; c++) {
            sb.append("─".repeat(widths[c] + 2));
            if (c < widths.length - 1) {
                sb.append(mid);
            }
        }
        sb.append(right);
        String border = sb.toString();
        return ansi ? "\u001B[2m" + border + "\u001B[0m" : border;
    }

    private static String formatTableRow(List<String> cells, int[] widths, boolean ansi) {
        StringBuilder sb = new StringBuilder();
        sb.append("│");
        for (int c = 0; c < widths.length; c++) {
            String text = c < cells.size() ? cells.get(c) : "";
            String truncated = TerminalWidthCalculator.truncateToWidth(text, widths[c], "…");
            String padded = TerminalWidthCalculator.padRight(truncated, widths[c]);
            sb.append(" ").append(padded).append(" │");
        }
        return sb.toString();
    }
}
