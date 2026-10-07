package com.xhlcli.context;

import com.xhlcli.model.ChatMessage;
import com.xhlcli.model.ContentPart;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenBudgetMultimodalTest {

    @Test
    void estimatesImageTokensAccurately() {
        ChatMessage textOnly = ChatMessage.user("Hello world");
        int textTokens = TokenBudget.estimateTokens(textOnly);
        // "Hello world" length is 11, 11 / 2.5 ceil = 5, overhead = 4 -> total 9
        assertEquals(9, textTokens);

        ChatMessage withImage = ChatMessage.user(List.of(
                ContentPart.text("Hello world"),
                ContentPart.imageBase64("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY44YAAAAASUVORK5CYII=", "image/png")
        ));
        int withImageTokens = TokenBudget.estimateTokens(withImage);
        // textTokens (9) + 1000 = 1009
        assertEquals(textTokens + TokenBudget.ESTIMATED_IMAGE_TOKENS, withImageTokens);
    }

    @Test
    void accountsForMultipleImagesInBudget() {
        ChatMessage multipleImages = ChatMessage.user(List.of(
                ContentPart.text("Analyze these two diagrams"),
                ContentPart.imageUrl("https://example.com/a.png"),
                ContentPart.imageUrl("https://example.com/b.png")
        ));

        int totalTokens = TokenBudget.estimateTokens(multipleImages);
        int expectedImageTokens = 2 * TokenBudget.ESTIMATED_IMAGE_TOKENS;
        assertTrue(totalTokens >= expectedImageTokens);

        // Window size 3000: reserved 2000 (resp) + 500 (sys) + 1000 (tools) -> available is negative!
        // Window size 8000: max 8000 -> small window reserves 2000, 500, 1000 -> available 4500
        TokenBudget budget = new TokenBudget(4000); // 4000 window: available conversation budget = 4000 - 1000 - 500 - 500 = 2000
        budget.updateContextWindow(4000);
        // multipleImages is > 2000 tokens
        assertFalse(budget.isWithinBudget(List.of(multipleImages)));

        budget.updateContextWindow(16000);
        // 16000 window: available conversation budget = 16000 - 2000 - 500 - 1000 = 12500
        assertTrue(budget.isWithinBudget(List.of(multipleImages)));
    }
}
