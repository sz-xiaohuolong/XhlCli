package com.xhlcli.cli.terminal;

import org.jline.reader.Highlighter;
import org.jline.reader.LineReader;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 终端实时输入语法高亮器。
 * 针对斜杠命令、@path 路径引用与疑似敏感密钥提供视觉高亮反馈。
 */
public final class TerminalInputHighlighter implements Highlighter {

    private static final Pattern SENSITIVE_TOKEN_PATTERN =
            Pattern.compile("(?i)(sk-[a-zA-Z0-9]{20,}|ghp_[a-zA-Z0-9]{36}|bearer\\s+[a-zA-Z0-9_\\-\\.]+)");
    private static final Pattern AT_PATH_PATTERN =
            Pattern.compile("@[a-zA-Z0-9_\\-\\./\\\\]+");

    private Pattern errorPattern;
    private int errorIndex = -1;

    @Override
    public AttributedString highlight(LineReader reader, String buffer) {
        if (buffer == null || buffer.isEmpty()) {
            return new AttributedString("");
        }

        AttributedStringBuilder asb = new AttributedStringBuilder();
        String trimmed = buffer.trim();

        // 1. 斜杠命令高亮
        if (buffer.startsWith("/")) {
            int firstSpace = buffer.indexOf(' ');
            if (firstSpace == -1) {
                // 单个命令词：高亮为黄色加粗
                asb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW).bold());
                asb.append(buffer);
                return asb.toAttributedString();
            } else {
                // 命令前缀高亮
                String cmd = buffer.substring(0, firstSpace);
                asb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW).bold());
                asb.append(cmd);

                // 剩余参数
                String remainder = buffer.substring(firstSpace);
                highlightArgs(asb, remainder);
                return asb.toAttributedString();
            }
        }

        // 2. 普通文本处理行内 @path 与敏感凭据
        highlightArgs(asb, buffer);
        return asb.toAttributedString();
    }

    private void highlightArgs(AttributedStringBuilder asb, String text) {
        int index = 0;
        int len = text.length();

        while (index < len) {
            // 检测 @path
            Matcher pathMatcher = AT_PATH_PATTERN.matcher(text);
            boolean pathFound = pathMatcher.find(index);

            // 检测敏感 Token
            Matcher secretMatcher = SENSITIVE_TOKEN_PATTERN.matcher(text);
            boolean secretFound = secretMatcher.find(index);

            int nextSpecial = len;
            int type = 0; // 0=none, 1=path, 2=secret
            int end = len;

            if (pathFound && pathMatcher.start() < nextSpecial) {
                nextSpecial = pathMatcher.start();
                end = pathMatcher.end();
                type = 1;
            }
            if (secretFound && secretMatcher.start() < nextSpecial) {
                nextSpecial = secretMatcher.start();
                end = secretMatcher.end();
                type = 2;
            }

            if (type == 0) {
                // 无特殊模式，追加剩余普通文本
                asb.style(AttributedStyle.DEFAULT);
                asb.append(text.substring(index));
                break;
            }

            // 追加特殊模式前的普通文本
            if (nextSpecial > index) {
                asb.style(AttributedStyle.DEFAULT);
                asb.append(text.substring(index, nextSpecial));
            }

            if (type == 1) {
                // @path 渲染为青色下划线
                asb.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN).underline());
                asb.append(text.substring(nextSpecial, end));
            } else if (type == 2) {
                // 敏感凭据渲染为黄色下划线告警
                asb.style(AttributedStyle.DEFAULT.background(AttributedStyle.YELLOW).foreground(AttributedStyle.BLACK).bold());
                asb.append(text.substring(nextSpecial, end));
            }

            index = end;
        }
    }

    @Override
    public void setErrorPattern(Pattern errorPattern) {
        this.errorPattern = errorPattern;
    }

    @Override
    public void setErrorIndex(int errorIndex) {
        this.errorIndex = errorIndex;
    }
}
