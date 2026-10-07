package com.xhlcli.runtime.task;

import java.util.List;

public final class TaskCommandFormatter {
    private TaskCommandFormatter() {}

    public static String handle(DurableTaskManager manager, String payload) {
        if (manager == null) {
            return "后台任务管理器未初始化。";
        }
        String normalized = payload == null || payload.isBlank() ? "list" : payload.trim();
        if (normalized.equalsIgnoreCase("list")) {
            return formatList(manager.list(20));
        }
        if (normalized.regionMatches(true, 0, "list ", 0, 5)) {
            return formatList(manager.list(parseLimit(normalized.substring(5).trim(), 20)));
        }
        if (normalized.equalsIgnoreCase("add") || normalized.regionMatches(true, 0, "add ", 0, 4)) {
            String prompt = normalized.length() > 3 ? normalized.substring(3).trim() : "";
            if (prompt.isBlank()) {
                return "❌ 任务内容不能为空。用法: /task add <任务内容>";
            }
            DurableTask task = manager.enqueue(prompt);
            return "✅ 后台任务已提交: " + task.id() + "\n   查看状态: /task log " + task.id();
        }
        if (normalized.equalsIgnoreCase("cancel") || normalized.regionMatches(true, 0, "cancel ", 0, 7)) {
            String id = normalized.length() > 6 ? normalized.substring(6).trim() : "";
            if (id.isBlank()) {
                return "❌ 任务 ID 不能为空。用法: /task cancel <task_id>";
            }
            return manager.cancel(id)
                    ? "⏹️ 已请求取消后台任务: " + id
                    : "❌ 未找到可取消的后台任务: " + id;
        }
        if (normalized.equalsIgnoreCase("log") || normalized.regionMatches(true, 0, "log ", 0, 4)) {
            String id = normalized.length() > 3 ? normalized.substring(3).trim() : "";
            if (id.isBlank()) {
                return "❌ 任务 ID 不能为空。用法: /task log <task_id>";
            }
            return manager.find(id)
                    .map(TaskCommandFormatter::formatLog)
                    .orElse("❌ 未找到后台任务: " + id);
        }
        return """
                ❌ 未知 /task 子命令: %s
                可用命令：
                  /task
                  /task list [N]
                  /task add <任务内容>
                  /task cancel <task_id>
                  /task log <task_id>
                """.formatted(payload).trim();
    }

    public static String formatList(List<DurableTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return "📭 暂无后台任务";
        }
        StringBuilder sb = new StringBuilder("📋 最近 ").append(tasks.size()).append(" 个后台任务：\n");
        for (DurableTask task : tasks) {
            sb.append("   ")
                    .append(task.id())
                    .append("  ")
                    .append(String.format("%-11s", task.status().value()))
                    .append("  ")
                    .append(task.durationMs())
                    .append("ms  ")
                    .append(task.shortPrompt())
                    .append('\n');
        }
        return sb.toString().trim();
    }

    public static String formatLog(DurableTask task) {
        StringBuilder sb = new StringBuilder();
        sb.append("📋 后台任务 ").append(task.id()).append('\n');
        sb.append("状态: ").append(task.status().value()).append('\n');
        sb.append("工作区: ").append(task.workspace() != null ? task.workspace() : "默认").append('\n');
        sb.append("创建: ").append(task.createdAt()).append('\n');
        if (task.startedAt() != null) {
            sb.append("开始: ").append(task.startedAt()).append('\n');
        }
        if (task.finishedAt() != null) {
            sb.append("结束: ").append(task.finishedAt()).append(" (").append(task.durationMs()).append("ms)\n");
        }
        sb.append("\n任务目标:\n").append(task.prompt()).append('\n');
        if (task.error() != null && !task.error().isBlank()) {
            sb.append("\n错误信息:\n").append(task.error()).append('\n');
        }
        if (task.result() != null && !task.result().isBlank()) {
            sb.append("\n执行结果:\n").append(task.result()).append('\n');
        }
        return sb.toString().trim();
    }

    private static int parseLimit(String raw, int defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
