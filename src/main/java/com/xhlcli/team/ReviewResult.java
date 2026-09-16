package com.xhlcli.team;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 结构化审查结论与容错解析。
 *
 * 审查结果分为三类：
 * - APPROVED: 质量合格，准予通过
 * - CHANGES_REQUESTED: 发现缺陷或不符项，打回执行者重新修正
 * - BLOCKED: 存在阻断性问题或无法继续
 */
public record ReviewResult(
        Status status,
        String summary,
        List<String> issues,
        List<String> suggestions
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public enum Status {
        APPROVED,
        CHANGES_REQUESTED,
        BLOCKED
    }

    public ReviewResult {
        if (status == null) {
            status = Status.CHANGES_REQUESTED;
        }
        if (summary == null) {
            summary = "";
        }
        if (issues == null) {
            issues = List.of();
        } else {
            issues = Collections.unmodifiableList(List.copyOf(issues));
        }
        if (suggestions == null) {
            suggestions = List.of();
        } else {
            suggestions = Collections.unmodifiableList(List.copyOf(suggestions));
        }
    }

    public boolean isApproved() {
        return status == Status.APPROVED;
    }

    /**
     * 将发现的问题格式化为清晰的反馈列表。
     */
    public String formatIssues() {
        if (issues.isEmpty()) {
            return summary.isBlank() ? "审查未通过，请重新检查执行结果。" : summary;
        }
        StringBuilder sb = new StringBuilder();
        if (!summary.isBlank()) {
            sb.append(summary).append("\n");
        }
        for (String issue : issues) {
            sb.append("- ").append(issue).append("\n");
        }
        if (!suggestions.isEmpty()) {
            sb.append("改进建议：\n");
            for (String suggestion : suggestions) {
                sb.append("- ").append(suggestion).append("\n");
            }
        }
        return sb.toString().trim();
    }

    public static ReviewResult approved(String summary) {
        return new ReviewResult(Status.APPROVED, summary, List.of(), List.of());
    }

    public static ReviewResult changesRequested(String summary, List<String> issues, List<String> suggestions) {
        return new ReviewResult(Status.CHANGES_REQUESTED, summary, issues, suggestions);
    }

    public static ReviewResult blocked(String summary, List<String> issues) {
        return new ReviewResult(Status.BLOCKED, summary, issues, List.of());
    }

    /**
     * 从模型返回的审查原始内容中解析 ReviewResult，带容错降级。
     */
    public static ReviewResult parse(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return new ReviewResult(Status.CHANGES_REQUESTED, "审查内容为空，保守判定为未通过",
                    List.of("审查结果为空"), List.of());
        }

        // 1. 尝试清洗代码块并以 JSON 解析
        String cleaned = cleanJsonContent(rawContent);
        try {
            JsonNode root = MAPPER.readTree(cleaned);
            if (root.isObject()) {
                JsonNode approvedNode = root.path("approved");
                boolean isApproved = approvedNode.asBoolean(false);

                // 兼容 status 字段
                String statusStr = root.path("status").asText("").toUpperCase(Locale.ROOT);
                if ("APPROVED".equals(statusStr)) {
                    isApproved = true;
                } else if ("BLOCKED".equals(statusStr)) {
                    isApproved = false;
                }

                String summary = root.path("summary").asText("");
                List<String> issues = extractStringList(root.path("issues"));
                List<String> suggestions = extractStringList(root.path("suggestions"));

                Status status;
                if (isApproved) {
                    status = Status.APPROVED;
                } else if ("BLOCKED".equals(statusStr)) {
                    status = Status.BLOCKED;
                } else {
                    status = Status.CHANGES_REQUESTED;
                }
                return new ReviewResult(status, summary, issues, suggestions);
            }
        } catch (Exception ignored) {
            // 非严格 JSON，降级走文本启发式解析
        }

        // 2. 文本启发式降级
        return parseHeuristic(rawContent);
    }

    private static ReviewResult parseHeuristic(String text) {
        String lower = text.toLowerCase(Locale.ROOT);

        boolean hasNegativeKeyword = lower.contains("未通过")
                || lower.contains("不通过")
                || lower.contains("不合格")
                || lower.contains("有问题")
                || lower.contains("存在缺陷")
                || lower.contains("\"approved\": false")
                || lower.contains("\"approved\":false")
                || lower.contains("changes_requested");

        boolean hasPositiveKeyword = lower.contains("通过")
                || lower.contains("合格")
                || lower.contains("符合要求")
                || lower.contains("\"approved\": true")
                || lower.contains("\"approved\":true")
                || lower.contains("approved");

        if (hasNegativeKeyword) {
            List<String> extractedIssues = extractIssuesFromText(text);
            return new ReviewResult(Status.CHANGES_REQUESTED, "审查未通过",
                    extractedIssues.isEmpty() ? List.of(text.trim()) : extractedIssues, List.of());
        }

        if (hasPositiveKeyword) {
            return new ReviewResult(Status.APPROVED, text.trim(), List.of(), List.of());
        }

        // 保守策略：无法识别且未明确表示通过，保守判为未通过
        List<String> issues = extractIssuesFromText(text);
        return new ReviewResult(Status.CHANGES_REQUESTED, "未能明确识别审查结果，保守判定为未通过",
                issues.isEmpty() ? List.of(text.trim()) : issues, List.of());
    }

    private static List<String> extractIssuesFromText(String text) {
        List<String> issues = new ArrayList<>();
        String[] lines = text.split("\r?\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.matches("^\\d+\\..*")) {
                String item = trimmed.replaceFirst("^[-*]\\s*|^\\d+\\.\\s*", "").trim();
                if (!item.isBlank()) {
                    issues.add(item);
                }
            }
        }
        return issues;
    }

    private static List<String> extractStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> list = new ArrayList<>();
        for (JsonNode item : node) {
            String val = item.asText("").trim();
            if (!val.isBlank()) {
                list.add(val);
            }
        }
        return list;
    }

    private static String cleanJsonContent(String content) {
        String cleaned = content.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        return cleaned.trim();
    }
}
