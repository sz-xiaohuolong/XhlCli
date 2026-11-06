package com.xhlcli.runtime.task;

import com.xhlcli.model.RunResult;
import java.util.Objects;

/** 保留真实终态，避免把空字符串或失败提示当成成功。 */
public record TaskOutcome(TaskStatus status, String result, String error) {
    public TaskOutcome {
        Objects.requireNonNull(status, "status");
        if (status != TaskStatus.COMPLETED && status != TaskStatus.CANCELED && status != TaskStatus.FAILED) {
            throw new IllegalArgumentException("任务结果必须是终态");
        }
        result = result == null ? "" : result;
        error = error == null ? "" : error;
    }

    public static TaskOutcome from(RunResult run) {
        return switch (run.status()) {
            case COMPLETED -> new TaskOutcome(TaskStatus.COMPLETED, run.finalAnswer(), "");
            case CANCELED -> new TaskOutcome(TaskStatus.CANCELED, run.finalAnswer(), run.reason());
            default -> new TaskOutcome(TaskStatus.FAILED, run.finalAnswer(), run.reason().isBlank() ? run.status().name() : run.reason());
        };
    }
}
