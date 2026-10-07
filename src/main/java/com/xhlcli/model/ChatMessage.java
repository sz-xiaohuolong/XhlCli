package com.xhlcli.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record ChatMessage(
        Role role,
        String content,
        List<ToolCall> toolCalls,
        String toolCallId,
        List<ContentPart> contentParts
) {
    public ChatMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
        toolCalls = List.copyOf(Objects.requireNonNull(toolCalls, "toolCalls"));
        contentParts = contentParts == null ? List.of() : List.copyOf(contentParts);

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

    public ChatMessage(Role role, String content, List<ToolCall> toolCalls, String toolCallId) {
        this(role, content, toolCalls, toolCallId, List.of());
    }

    public ChatMessage(Role role, String content) {
        this(role, content, List.of(), null, List.of());
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content);
    }

    public static ChatMessage user(List<ContentPart> contentParts) {
        String plain = plainText(contentParts);
        return new ChatMessage(Role.USER, plain.isBlank() ? "[图片附件]" : plain, List.of(), null, contentParts);
    }

    public static ChatMessage user(String content, List<ContentPart> contentParts) {
        return new ChatMessage(Role.USER, content, List.of(), null, contentParts);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content);
    }

    public static ChatMessage assistant(String content, List<ToolCall> toolCalls) {
        return new ChatMessage(Role.ASSISTANT, content, toolCalls, null, List.of());
    }

    public static ChatMessage tool(String toolCallId, String content) {
        return new ChatMessage(Role.TOOL, content, List.of(), toolCallId, List.of());
    }

    public boolean hasContentParts() {
        return contentParts != null && !contentParts.isEmpty();
    }

    public boolean hasImages() {
        return hasContentParts() && contentParts.stream().anyMatch(ContentPart::isImage);
    }

    public int imagePartCount() {
        if (!hasContentParts()) {
            return 0;
        }
        int count = 0;
        for (ContentPart part : contentParts) {
            if (part != null && part.isImage()) {
                count++;
            }
        }
        return count;
    }

    public ChatMessage withoutImageContent() {
        return withoutImageContent("当前模型不支持视觉输入，已省略 {count} 张图片附件。建议使用 /model use 切换多模态模型。");
    }

    public ChatMessage withoutImageContent(String noticeTemplate) {
        if (!hasImages()) {
            return this;
        }
        List<ContentPart> stripped = new ArrayList<>();
        int omitted = 0;
        for (ContentPart part : contentParts) {
            if (part == null) {
                continue;
            }
            if (part.isImage()) {
                omitted++;
            } else {
                stripped.add(part);
            }
        }
        String notice = noticeTemplate == null || noticeTemplate.isBlank()
                ? "图片附件已省略 {count} 张。"
                : noticeTemplate;
        stripped.add(ContentPart.text("[" + notice.replace("{count}", String.valueOf(omitted)) + "]"));
        String newContent = plainText(stripped);
        if (newContent.isBlank()) {
            newContent = "[" + notice.replace("{count}", String.valueOf(omitted)) + "]";
        }
        return new ChatMessage(role, newContent, toolCalls, toolCallId, List.copyOf(stripped));
    }

    private static String plainText(List<ContentPart> parts) {
        if (parts == null || parts.isEmpty()) {
            return "";
        }
        List<String> texts = new ArrayList<>();
        for (ContentPart p : parts) {
            if (p != null && p.isText() && p.text() != null && !p.text().isBlank()) {
                texts.add(p.text());
            }
        }
        return String.join("\n", texts);
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
