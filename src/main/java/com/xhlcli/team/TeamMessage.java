package com.xhlcli.team;

import java.time.Instant;
import java.util.Objects;

/**
 * Team Agent 间通信消息 - Multi-Agent 协作的基本通信单元。
 *
 * 消息类型：
 * - TASK: 主控分配给子代理的任务
 * - RESULT: 子代理返回的执行结果
 * - FEEDBACK: 审查者对结果的反馈（包含修改建议）
 * - APPROVAL: 审查者认可通过
 * - REJECTION: 审查者拒绝结果，需要打回重试
 * - ERROR: 系统级错误（如 LLM 调用失败）
 */
public record TeamMessage(
        String fromAgent,
        TeamRole fromRole,
        String content,
        Type type,
        long timestamp
) {
    public enum Type {
        TASK,
        RESULT,
        FEEDBACK,
        APPROVAL,
        REJECTION,
        ERROR
    }

    public TeamMessage {
        Objects.requireNonNull(fromAgent, "fromAgent cannot be null");
        Objects.requireNonNull(type, "type cannot be null");
        if (content == null) {
            content = "";
        }
    }

    public static TeamMessage task(String fromAgent, String content) {
        return new TeamMessage(fromAgent, null, content, Type.TASK, Instant.now().toEpochMilli());
    }

    public static TeamMessage result(String fromAgent, TeamRole role, String content) {
        return new TeamMessage(fromAgent, role, content, Type.RESULT, Instant.now().toEpochMilli());
    }

    public static TeamMessage feedback(String fromAgent, String content) {
        return new TeamMessage(fromAgent, TeamRole.REVIEWER, content, Type.FEEDBACK, Instant.now().toEpochMilli());
    }

    public static TeamMessage approval(String fromAgent, String content) {
        return new TeamMessage(fromAgent, TeamRole.REVIEWER, content, Type.APPROVAL, Instant.now().toEpochMilli());
    }

    public static TeamMessage rejection(String fromAgent, String content) {
        return new TeamMessage(fromAgent, TeamRole.REVIEWER, content, Type.REJECTION, Instant.now().toEpochMilli());
    }

    public static TeamMessage error(String fromAgent, TeamRole role, String content) {
        return new TeamMessage(fromAgent, role, content, Type.ERROR, Instant.now().toEpochMilli());
    }
}
