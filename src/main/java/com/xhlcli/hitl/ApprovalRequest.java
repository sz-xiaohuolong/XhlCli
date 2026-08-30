package com.xhlcli.hitl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 审批请求数据载体：描述待人工确认的工具调用信息。
 */
public record ApprovalRequest(
        String toolName,
        String arguments,
        String dangerLevel,
        String riskDescription,
        String suggestion
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int BOX_INNER_WIDTH = 58;
    private static final int ARG_LINE_WIDTH = BOX_INNER_WIDTH - 6;
    private static final int MAX_LONG_VALUE_PREVIEW = 120;

    public static ApprovalRequest of(String toolName, String arguments, String suggestion) {
        return new ApprovalRequest(
                toolName,
                arguments,
                ApprovalPolicy.getDangerLevel(toolName),
                ApprovalPolicy.getRiskDescription(toolName),
                suggestion
        );
    }

    public static ApprovalRequest of(String toolName, String arguments) {
        return of(toolName, arguments, null);
    }

    /**
     * 格式化为美观的终端审批框文本，基于 CJK/Emoji 显示宽度精确对齐。
     */
    public String toDisplayText() {
        StringBuilder sb = new StringBuilder();
        String border = "─".repeat(BOX_INNER_WIDTH);
        sb.append("┌").append(border).append("┐\n");
        sb.append(formatBoxLine("⚠️  需要审批")).append("\n");
        sb.append("├").append(border).append("┤\n");
        sb.append(formatBoxField("工具", toolName)).append("\n");
        sb.append(formatBoxField("等级", dangerLevel)).append("\n");
        sb.append(formatBoxField("风险", riskDescription)).append("\n");
        sb.append("├").append(border).append("┤\n");
        sb.append(formatBoxLine("参数:")).append("\n");
        for (String line : formatArgs(arguments)) {
            sb.append(formatBoxIndented(line)).append("\n");
        }
        if (suggestion != null && !suggestion.isBlank()) {
            sb.append("├").append(border).append("┤\n");
            sb.append(formatBoxLine("执行理由:")).append("\n");
            for (String line : wrapByDisplayWidth(suggestion, ARG_LINE_WIDTH)) {
                sb.append(formatBoxIndented(line)).append("\n");
            }
        }
        sb.append("└").append(border).append("┘");
        return sb.toString();
    }

    private String formatBoxField(String prefix, String value) {
        String label = prefix + ": ";
        String safeValue = value == null ? "" : value;
        int used = displayWidth(label) + 2;  // "│  " 占 2 列
        int target = BOX_INNER_WIDTH - used;
        String truncated = truncateByDisplayWidth(safeValue, target);
        String padded = padRightByDisplayWidth(truncated, target);
        return "│  " + label + padded + "│";
    }

    private String formatBoxLine(String text) {
        String safe = text == null ? "" : text;
        int target = BOX_INNER_WIDTH - 2;
        String truncated = truncateByDisplayWidth(safe, target);
        String padded = padRightByDisplayWidth(truncated, target);
        return "│  " + padded + "│";
    }

    private String formatBoxIndented(String text) {
        String safe = text == null ? "" : text;
        int target = BOX_INNER_WIDTH - 4;  // "│    " 占 4 列
        String truncated = truncateByDisplayWidth(safe, target);
        String padded = padRightByDisplayWidth(truncated, target);
        return "│    " + padded + "│";
    }

    private List<String> formatArgs(String args) {
        List<String> lines = new ArrayList<>();
        if (args == null || args.isBlank()) {
            lines.add("(无参数)");
            return lines;
        }
        try {
            JsonNode root = MAPPER.readTree(args);
            if (root.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> it = root.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> entry = it.next();
                    String key = entry.getKey();
                    JsonNode valNode = entry.getValue();
                    if (valNode.isTextual()) {
                        String v = valNode.asText();
                        if (v.length() > MAX_LONG_VALUE_PREVIEW) {
                            String head = v.substring(0, MAX_LONG_VALUE_PREVIEW).replace("\n", "⏎");
                            lines.addAll(wrapByDisplayWidth(
                                    key + ": \"" + head + "...\" (" + v.length() + " 字符)",
                                    ARG_LINE_WIDTH));
                        } else {
                            String v1 = v.replace("\n", "⏎");
                            lines.addAll(wrapByDisplayWidth(key + ": \"" + v1 + "\"", ARG_LINE_WIDTH));
                        }
                    } else {
                        lines.addAll(wrapByDisplayWidth(key + ": " + valNode.toString(), ARG_LINE_WIDTH));
                    }
                }
                if (lines.isEmpty()) {
                    lines.add("(空对象)");
                }
                return lines;
            }
        } catch (Exception ignored) {
            // 非 JSON 退回原样
        }
        return wrapByDisplayWidth(args.trim(), ARG_LINE_WIDTH);
    }

    public static int displayWidth(String s) {
        if (s == null) return 0;
        int w = 0;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 0x20 || cp == 0x7F) {
                continue;
            }
            if (isWideCodePoint(cp)) {
                w += 2;
            } else {
                w += 1;
            }
        }
        return w;
    }

    private static boolean isWideCodePoint(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F)
                || (cp >= 0x2E80 && cp <= 0x9FFF)
                || (cp >= 0xA000 && cp <= 0xA4CF)
                || (cp >= 0xAC00 && cp <= 0xD7A3)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFE30 && cp <= 0xFE4F)
                || (cp >= 0xFF00 && cp <= 0xFF60)
                || (cp >= 0xFFE0 && cp <= 0xFFE6)
                || (cp >= 0x2600 && cp <= 0x27BF)
                || (cp >= 0x1F300 && cp <= 0x1FAFF);
    }

    public static String padRightByDisplayWidth(String s, int targetCols) {
        int w = displayWidth(s);
        if (w >= targetCols) {
            return s;
        }
        return s + " ".repeat(targetCols - w);
    }

    public static String truncateByDisplayWidth(String s, int targetCols) {
        if (s == null) return "";
        if (displayWidth(s) <= targetCols) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        int used = 0;
        int reserve = 3;  // "..."
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int cpWidth = isWideCodePoint(cp) ? 2 : 1;
            if (used + cpWidth > targetCols - reserve) {
                break;
            }
            sb.appendCodePoint(cp);
            used += cpWidth;
            i += Character.charCount(cp);
        }
        sb.append("...");
        return sb.toString();
    }

    public static List<String> wrapByDisplayWidth(String text, int lineWidth) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) {
            lines.add("");
            return lines;
        }
        StringBuilder current = new StringBuilder();
        int used = 0;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int cpWidth = isWideCodePoint(cp) ? 2 : 1;
            if (used + cpWidth > lineWidth) {
                lines.add(current.toString());
                current.setLength(0);
                used = 0;
            }
            current.appendCodePoint(cp);
            used += cpWidth;
            i += Character.charCount(cp);
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        return lines;
    }
}
