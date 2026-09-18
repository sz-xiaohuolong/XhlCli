package com.xhlcli.context;

import com.xhlcli.model.ChatMessage;
import com.xhlcli.model.ToolCall;

import java.util.List;

/**
 * Token 预算管理器。
 * 负责估算并维护单次请求的上下文资源限制。
 */
public class TokenBudget {
    private volatile int contextWindow;
    private volatile int reservedForResponse;
    private volatile int reservedForSystem;
    private volatile int reservedForTools;

    public TokenBudget(int contextWindow, int reservedForResponse, int reservedForSystem, int reservedForTools) {
        this.contextWindow = contextWindow;
        this.reservedForResponse = reservedForResponse;
        this.reservedForSystem = reservedForSystem;
        this.reservedForTools = reservedForTools;
    }
    
    public TokenBudget(int contextWindow) {
        this(contextWindow, 2000, 500, 1000);
    }

    public void updateContextWindow(int newContextWindow) {
        if (newContextWindow <= 0) {
            throw new IllegalArgumentException("newContextWindow must be positive: " + newContextWindow);
        }
        this.contextWindow = newContextWindow;
        if (newContextWindow <= 16384) {
            this.reservedForResponse = Math.min(2000, newContextWindow / 4);
            this.reservedForSystem = Math.min(500, newContextWindow / 8);
            this.reservedForTools = Math.min(1000, newContextWindow / 8);
        } else {
            this.reservedForResponse = 2000;
            this.reservedForSystem = 500;
            this.reservedForTools = 1000;
        }
    }

    public int getContextWindow() {
        return contextWindow;
    }

    public int getAvailableForConversation() {
        return contextWindow - reservedForSystem - reservedForTools - reservedForResponse;
    }

    public boolean isWithinBudget(List<ChatMessage> messages) {
        return estimateTokens(messages) <= getAvailableForConversation();
    }

    /**
     * 按字符数进行简易 Token 估算：通常 1 token 约 4 个英文字符或 1.5 个中文字符。
     * 此处使用一个保守的算法：1 token = 2.5 字符。
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / 2.5);
    }

    public static int estimateTokens(List<ChatMessage> messages) {
        if (messages == null) return 0;
        int total = 0;
        for (ChatMessage msg : messages) {
            total += 4; // per message overhead
            total += estimateTokens(msg.content());
            if (msg.toolCalls() != null) {
                for (ToolCall tc : msg.toolCalls()) {
                    total += estimateTokens(tc.name());
                    total += estimateTokens(tc.argumentsJson());
                }
            }
        }
        return total;
    }
}
