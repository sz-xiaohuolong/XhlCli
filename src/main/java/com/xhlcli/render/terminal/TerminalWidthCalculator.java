package com.xhlcli.render.terminal;

import org.jline.utils.WCWidth;

import java.util.regex.Pattern;

/**
 * 终端字符显示列宽计算器。
 * 针对中文字符（全角）、Emoji 与西文字符（半角）提供准确的列宽计算与定宽安全截断，忽略 ANSI 转义序列。
 */
public final class TerminalWidthCalculator {

    private static final Pattern ANSI_PATTERN = Pattern.compile("\u001B\\[[;?0-9]*[a-zA-Z]");

    private TerminalWidthCalculator() {}

    /**
     * 剥离 ANSI 转义字符序列后的纯文本。
     */
    public static String stripAnsi(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return ANSI_PATTERN.matcher(text).replaceAll("");
    }

    /**
     * 计算字符串在终端中实际占用的显示列宽。
     */
    public static int displayWidth(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        String clean = stripAnsi(text);
        int width = 0;
        int length = clean.length();
        int offset = 0;
        while (offset < length) {
            int codePoint = clean.codePointAt(offset);
            int charCount = Character.charCount(codePoint);
            int w = WCWidth.wcwidth(codePoint);
            if (w > 0) {
                width += w;
            }
            offset += charCount;
        }
        return width;
    }

    /**
     * 将字符串安全截断至指定显示列宽，并在超出时附加指定省略后缀。
     */
    public static String truncateToWidth(String text, int maxWidth, String suffix) {
        if (text == null || maxWidth <= 0) {
            return "";
        }
        String cleanSuffix = suffix == null ? "…" : suffix;
        int suffixWidth = displayWidth(cleanSuffix);

        if (displayWidth(text) <= maxWidth) {
            return text;
        }

        if (maxWidth <= suffixWidth) {
            return cleanSuffix;
        }

        int targetContentWidth = maxWidth - suffixWidth;
        StringBuilder sb = new StringBuilder();
        int currentWidth = 0;
        int length = text.length();
        int offset = 0;

        while (offset < length) {
            int codePoint = text.codePointAt(offset);
            int charCount = Character.charCount(codePoint);
            int w = WCWidth.wcwidth(codePoint);
            int cell = w > 0 ? w : 0;

            if (currentWidth + cell > targetContentWidth) {
                break;
            }

            sb.appendCodePoint(codePoint);
            currentWidth += cell;
            offset += charCount;
        }

        sb.append(cleanSuffix);
        return sb.toString();
    }

    /**
     * 向右填充空格以达到目标终端列宽。
     */
    public static String padRight(String text, int targetWidth) {
        if (text == null) {
            text = "";
        }
        int curWidth = displayWidth(text);
        if (curWidth >= targetWidth) {
            return text;
        }
        return text + " ".repeat(targetWidth - curWidth);
    }
}
