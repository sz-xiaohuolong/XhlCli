package com.xhlcli.memory;

import com.xhlcli.llm.CancellationToken;
import com.xhlcli.llm.LlmClient;
import com.xhlcli.llm.LlmException;
import com.xhlcli.llm.StreamListener;
import com.xhlcli.model.ChatMessage;
import com.xhlcli.model.ChatResponse;
import com.xhlcli.model.TokenUsage;
import com.xhlcli.model.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConversationHistoryCompactorTest {

    @Test
    void testCompactDoesNotSplitToolCallAndResult() {
        LlmClient fakeClient = new LlmClient() {
            @Override
            public ChatResponse stream(List<ChatMessage> messages, List<ToolDefinition> tools, StreamListener listener, CancellationToken cancellationToken) throws LlmException {
                return new ChatResponse("Fake summary", new TokenUsage(10, 10, false));
            }
        };

        ConversationHistoryCompactor compactor = new ConversationHistoryCompactor(fakeClient);

        List<ChatMessage> history = List.of(
                ChatMessage.user("msg 1"),
                ChatMessage.assistant("msg 2"),
                ChatMessage.user("msg 3"),
                ChatMessage.assistant("tool call", List.of(new com.xhlcli.model.ToolCall("call_1", "test", "{}"))),
                ChatMessage.tool("call_1", "tool result 1"),
                ChatMessage.tool("call_2", "tool result 2") // Assume multiple tools in one call
        );

        // Try to keep last 2 messages. The last 2 are tool results.
        // It should shift splitIndex back to include the assistant message that initiated them.
        List<ChatMessage> compacted = compactor.compact(history, 2);
        
        // Before compacting: size = 6
        // Keep 2 originally means split at index 4 (tool_result 1). But since index 4 is TOOL, and index 3 is ASSISTANT with tool calls, 
        // the split should be at index 3.
        // So it compacts index 0,1,2 into 1 summary message.
        // The remaining are index 3,4,5. Total 4 messages.
        assertEquals(4, compacted.size());
        assertEquals("【之前会话的压缩摘要】\nFake summary", compacted.get(0).content());
        assertEquals(ChatMessage.Role.ASSISTANT, compacted.get(1).role());
        assertEquals(ChatMessage.Role.TOOL, compacted.get(2).role());
        assertEquals(ChatMessage.Role.TOOL, compacted.get(3).role());
    }
}
