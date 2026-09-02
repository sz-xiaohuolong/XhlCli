package com.xhlcli.context;

import com.xhlcli.memory.MemoryEntry;
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
                List.of(MemoryEntry.create("User likes coffee", "global", "user")),
                List.of(ChatMessage.system("Summary")),
                List.of(ChatMessage.user("Hello"))
        );

        assertEquals(3, context.size());
        
        // System prompt contains all parts including long-term memory
        String sysText = context.get(0).content();
        assertTrue(sysText.contains("Base rules"));
        assertTrue(sysText.contains("Tool rules"));
        assertTrue(sysText.contains("Project rules"));
        assertTrue(sysText.contains("User likes coffee"));
        assertTrue(sysText.contains("Long-Term Memory"));
        
        // Followed by summary, input
        assertEquals("Summary", context.get(1).content());
        assertEquals("Hello", context.get(2).content());
    }
}
