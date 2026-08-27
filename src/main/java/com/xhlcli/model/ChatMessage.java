package com.xhlcli.model;

import java.util.List;
import java.util.Objects;

public record ChatMessage(Role role, String content, List<ToolCall> toolCalls, String toolCallId) {
    public ChatMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
        toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls"));

        switch (role) {
            case SYSTEM, USER -> {
                requireNonBlank(content, "Message content");
                requireEmpty(toolCalls, "System and user messages must not contain tool calls");
                requireNull(toolCallId, "System and user messages must not contain a tool call ID");
            }
            case ASSISTANT -> {
                if (content.isBlank() && toolCalls.isEmpty()) {
                    throw new IllegalArgumentException("Assistant messages must contain text or tool calls");
                }
                requireNull(toolCallId, "Assistant messages must not contain a tool call ID");
            }
            case TOOL -> {
                requireNonBlank(content, "Tool observation");
                requireEmpty(toolCalls, "Tool messages must not contain tool calls");
                requireNonBlank(toolCallId, "Tool call ID");
            }
        }
    }

    public ChatMessage(Role role, String content) {
        this(role, content, List.of(), null);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content);
    }

    public static ChatMessage assistant(String content, List<ToolCall> toolCalls) {
        return new ChatMessage(Role.ASSISTANT, content, toolCalls, null);
    }

    public static ChatMessage tool(String toolCallId, String content) {
        return new ChatMessage(Role.TOOL, content, List.of(), toolCallId);
    }

    private static void requireNonBlank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
    }

    private static void requireEmpty(List<?> values, String message) {
        if (!values.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void requireNull(Object value, String message) {
        if (value != null) {
            throw new IllegalArgumentException(message);
        }
    }

    public enum Role {
        SYSTEM("system"),
        USER("user"),
        ASSISTANT("assistant"),
        TOOL("tool");

        private final String wireName;

        Role(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }
}
