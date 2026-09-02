package com.xhlcli.context;

import com.xhlcli.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TokenBudgetTest {

    @Test
    void testEstimateTokens() {
        assertEquals(0, TokenBudget.estimateTokens(""));
        assertEquals(2, TokenBudget.estimateTokens("test"));
        assertEquals(4, TokenBudget.estimateTokens("test test"));
        
        List<ChatMessage> msgs = List.of(
            ChatMessage.user("hello"),
            ChatMessage.assistant("world")
        );
        // 4 per msg + ceil(5/2.5)=2
        // msg1 = 4 + 2 = 6
        // msg2 = 4 + 2 = 6
        // total = 12
        assertEquals(12, TokenBudget.estimateTokens(msgs));
    }

    @Test
    void testBudgetCalculations() {
        TokenBudget budget = new TokenBudget(10000, 2000, 500, 1000);
        assertEquals(10000, budget.getContextWindow());
        assertEquals(10000 - 2000 - 500 - 1000, budget.getAvailableForConversation());
        
        assertTrue(budget.isWithinBudget(List.of(ChatMessage.user("A short message"))));
    }
}
