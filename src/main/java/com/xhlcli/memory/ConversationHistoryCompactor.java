package com.xhlcli.memory;

import com.xhlcli.llm.CancellationToken;
import com.xhlcli.llm.LlmClient;
import com.xhlcli.model.ChatMessage;
import com.xhlcli.model.ChatResponse;

import java.util.ArrayList;
import java.util.List;

public class ConversationHistoryCompactor {

    private static final String COMPACT_PROMPT = """
            请将以下会话历史压缩为一段摘要。
            摘要必须包含：用户目标、已完成事项、修改文件、关键决策、验证结果、未完成事项、失败原因、下一步和不可违反的约束。
            不要省略任何未完成的任务或之前约定的代码约束。
            """;

    private final LlmClient llmClient;

    public ConversationHistoryCompactor(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * 将会话前部压缩，保留尾部最新的 keepCount 条消息。
     * 注意：不可拆散 tool_call (ASSISTANT) 和 tool_result (TOOL) 对。
     */
    public List<ChatMessage> compact(List<ChatMessage> history, int keepCount) {
        if (history.size() <= keepCount + 1) {
            return history; // 没有足够的空间进行压缩
        }

        // 确定分割点。从 history.size() - keepCount 往前找，如果落在 TOOL 上，必须把对应的 ASSISTANT 一起保留
        int splitIndex = history.size() - keepCount;
        while (splitIndex > 0 && splitIndex < history.size() && history.get(splitIndex).role() == ChatMessage.Role.TOOL) {
            splitIndex--;
        }

        if (splitIndex <= 0) {
            return history; // 说明最近的都是 tool 连续调用，无法截断
        }

        List<ChatMessage> toCompact = history.subList(0, splitIndex);
        List<ChatMessage> toKeep = history.subList(splitIndex, history.size());

        String summary = generateSummary(toCompact);
        
        List<ChatMessage> result = new ArrayList<>();
        result.add(ChatMessage.system("【之前会话的压缩摘要】\n" + summary));
        result.addAll(toKeep);
        return result;
    }

    private String generateSummary(List<ChatMessage> toCompact) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(COMPACT_PROMPT));
        messages.addAll(toCompact);

        try {
            ChatResponse response = llmClient.stream(messages, delta -> {}, new CancellationToken());
            return response.content();
        } catch (Exception e) {
            return "摘要生成失败: " + e.getMessage();
        }
    }
}
