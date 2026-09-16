package com.xhlcli.team;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 最小化上下文交接包（Handover Package）。
 *
 * 规范 Agent 间任务移交格式，杜绝全量对话历史交叉同步。
 * 仅传递目标、范围、依赖结果与验收标准。
 */
public record HandoverPackage(
        String taskId,
        String taskGoal,
        String contextSummary,
        String acceptanceCriteria,
        List<String> filesModified
) {
    public HandoverPackage {
        Objects.requireNonNull(taskId, "taskId cannot be null");
        Objects.requireNonNull(taskGoal, "taskGoal cannot be null");
        if (contextSummary == null) {
            contextSummary = "";
        }
        if (acceptanceCriteria == null) {
            acceptanceCriteria = "";
        }
        if (filesModified == null) {
            filesModified = List.of();
        } else {
            filesModified = Collections.unmodifiableList(List.copyOf(filesModified));
        }
    }

    public static Builder builder(String taskId, String taskGoal) {
        return new Builder(taskId, taskGoal);
    }

    /**
     * 将交接包格式化为易于 LLM 理解的结构化 Prompt 文本。
     */
    public String toPromptText() {
        StringBuilder sb = new StringBuilder();
        sb.append("### 任务交接上下文 [").append(taskId).append("]\n");
        sb.append("**当前任务目标**：").append(taskGoal).append("\n\n");

        if (!contextSummary.isBlank()) {
            sb.append("**前置上下文与依赖结果**：\n").append(contextSummary.trim()).append("\n\n");
        }

        if (!acceptanceCriteria.isBlank()) {
            sb.append("**验收标准**：\n").append(acceptanceCriteria.trim()).append("\n\n");
        }

        if (!filesModified.isEmpty()) {
            sb.append("**涉及或修改的文件**：\n");
            for (String file : filesModified) {
                sb.append("- ").append(file).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString().trim();
    }

    public static final class Builder {
        private final String taskId;
        private final String taskGoal;
        private String contextSummary = "";
        private String acceptanceCriteria = "";
        private List<String> filesModified = List.of();

        public Builder(String taskId, String taskGoal) {
            this.taskId = taskId;
            this.taskGoal = taskGoal;
        }

        public Builder contextSummary(String contextSummary) {
            this.contextSummary = contextSummary == null ? "" : contextSummary;
            return this;
        }

        public Builder acceptanceCriteria(String acceptanceCriteria) {
            this.acceptanceCriteria = acceptanceCriteria == null ? "" : acceptanceCriteria;
            return this;
        }

        public Builder filesModified(List<String> filesModified) {
            this.filesModified = filesModified == null ? List.of() : List.copyOf(filesModified);
            return this;
        }

        public HandoverPackage build() {
            return new HandoverPackage(taskId, taskGoal, contextSummary, acceptanceCriteria, filesModified);
        }
    }
}
