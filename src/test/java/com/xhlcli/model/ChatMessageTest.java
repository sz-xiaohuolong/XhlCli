package com.xhlcli.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMessageTest {

    @Test
    void exposesProviderWireRoleNames() {
        assertEquals("system", ChatMessage.Role.SYSTEM.wireName());
        assertEquals("user", ChatMessage.Role.USER.wireName());
        assertEquals("assistant", ChatMessage.Role.ASSISTANT.wireName());
        assertEquals("tool", ChatMessage.Role.TOOL.wireName());
    }

    @Test
    void rejectsNullRoleAndBlankContent() {
        assertThrows(NullPointerException.class, () -> new ChatMessage(null, "hello"));
        assertThrows(IllegalArgumentException.class, () -> new ChatMessage(ChatMessage.Role.USER, "  "));
    }

    @Test
    void responseAndUsageAreImmutableValues() {
        TokenUsage usage = new TokenUsage(7, 2, true);
        ChatResponse response = new ChatResponse("hello", usage);

        assertEquals("hello", response.content());
        assertEquals(usage, response.usage());
        assertEquals(new ChatResponse("hello", new TokenUsage(7, 2, true)), response);
        assertFalse(TokenUsage.unknown().known());
        assertEquals(0, TokenUsage.unknown().inputTokens());
        assertEquals(0, TokenUsage.unknown().outputTokens());
    }

    @Test
    void rejectsInvalidResponseAndUsageValues() {
        assertThrows(IllegalArgumentException.class, () -> new TokenUsage(-1, 0, true));
        assertThrows(NullPointerException.class, () -> new ChatResponse(null, TokenUsage.unknown()));
        assertThrows(NullPointerException.class, () -> new ChatResponse("hello", null));
    }

    @Test
    void representsAssistantToolCallsAndToolObservations() {
        ToolCall call = new ToolCall("call_1", "echo_text", "{\"text\":\"hello\"}");
        ChatMessage assistant = ChatMessage.assistant("checking", List.of(call));
        ChatMessage observation = ChatMessage.tool("call_1", "{\"status\":\"success\"}");

        assertEquals(List.of(call), assistant.toolCalls());
        assertEquals(ChatMessage.Role.TOOL, observation.role());
        assertEquals("call_1", observation.toolCallId());
        assertThrows(IllegalArgumentException.class, () -> ChatMessage.assistant("", List.of()));
        assertThrows(IllegalArgumentException.class, () -> ChatMessage.tool("", "result"));
        assertThrows(UnsupportedOperationException.class, () -> assistant.toolCalls().add(call));
    }

    @Test
    void permitsEmptyResponseTextOnlyWhenItContainsToolCalls() {
        ToolCall call = new ToolCall("call_1", "echo_text", "{\"text\":\"hello\"}");
        ChatResponse response = new ChatResponse("", List.of(call), TokenUsage.unknown());

        assertTrue(response.hasToolCalls());
        assertThrows(IllegalArgumentException.class,
                () -> new ChatResponse("", List.of(), TokenUsage.unknown()));
    }
}
