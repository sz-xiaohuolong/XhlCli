package com.xhlcli.prompt;

import java.util.Objects;

/**
 * 代表 Prompt 分层中的一个独立提示块。
 */
public final class PromptBlock {
    private final PromptLayer layer;
    private final PromptSource source;
    private final String title;
    private final String content;
    private final boolean truncated;
    private final int originalLength;

    public PromptBlock(
            PromptLayer layer,
            PromptSource source,
            String title,
            String content,
            boolean truncated,
            int originalLength) {
        this.layer = Objects.requireNonNull(layer, "layer");
        this.source = Objects.requireNonNull(source, "source");
        this.title = title != null ? title.trim() : "";
        this.content = content != null ? content.trim() : "";
        this.truncated = truncated;
        this.originalLength = originalLength;
    }

    public static PromptBlock of(PromptLayer layer, PromptSource source, String title, String content) {
        String trimmed = content != null ? content.trim() : "";
        return new PromptBlock(layer, source, title, trimmed, false, trimmed.length());
    }

    public static PromptBlock truncated(PromptLayer layer, PromptSource source, String title, String content, int originalLength) {
        String trimmed = content != null ? content.trim() : "";
        return new PromptBlock(layer, source, title, trimmed, true, originalLength);
    }

    public PromptLayer layer() {
        return layer;
    }

    public PromptSource source() {
        return source;
    }

    public String title() {
        return title;
    }

    public String content() {
        return content;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public int originalLength() {
        return originalLength;
    }

    public int length() {
        return content.length();
    }

    public boolean isEmpty() {
        return content.isEmpty();
    }

    /**
     * 格式化输出为可注入 System Prompt 的 Markdown 文本。
     */
    public String render() {
        if (content.isEmpty()) {
            return "";
        }
        if (title.isEmpty()) {
            return content;
        }
        return title + "\n" + content;
    }

    @Override
    public String toString() {
        return "PromptBlock{" +
                "layer=" + layer +
                ", source=" + source +
                ", title='" + title + '\'' +
                ", length=" + content.length() +
                ", truncated=" + truncated +
                '}';
    }
}
