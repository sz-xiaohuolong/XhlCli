package com.xhlcli.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatMessageTest {

    @Test
    void exposesProviderWireRoleNames() {
        assertEquals("system", ChatMessage.Role.SYSTEM.wireName());
        assertEquals("user", ChatMessage.Role.USER.wireName());
        assertEquals("assistant", ChatMessage.Role.ASSISTANT.wireName());
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
}
