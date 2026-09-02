package com.xhlcli.context;

import com.xhlcli.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextAssemblerTest {

    @Test
    void testAssemble() {
        TokenBudget budget = new TokenBudget(10000);
        ContextAssembler assembler = new ContextAssembler(budget);

        List<ChatMessage> context = assembler.assemble(
                "Base rules",
                "Tool rules",
                "Mode",
                "Runtime",
                "Project rules",
                List.of(ChatMessage.system("Memory 1")),
                List.of(ChatMessage.system("Summary")),
                List.of(ChatMessage.user("Hello"))
        );

        assertEquals(4, context.size());
        
        // System prompt contains all 5 parts
        String sysText = context.get(0).content();
        assertTrue(sysText.contains("Base rules"));
        assertTrue(sysText.contains("Tool rules"));
        assertTrue(sysText.contains("Project rules"));
        
        // Followed by memory, summary, input
        assertEquals("Memory 1", context.get(1).content());
        assertEquals("Summary", context.get(2).content());
        assertEquals("Hello", context.get(3).content());
    }
}
