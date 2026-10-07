package com.xhlcli.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMessageMultimodalTest {

    @Test
    void shouldCreateAndInspectContentParts() {
        ContentPart textPart = ContentPart.text("查看该错误截图");
        assertTrue(textPart.isText());
        assertFalse(textPart.isImage());
        assertEquals("查看该错误截图", textPart.text());

        ContentPart base64Part = ContentPart.imageBase64("iVBORw0KGgoAAAANSUhEUg==", "image/png");
        assertFalse(base64Part.isText());
        assertTrue(base64Part.isImage());
        assertEquals("image_base64", base64Part.type());
        assertEquals("image/png", base64Part.mimeType());
        assertEquals("iVBORw0KGgoAAAANSUhEUg==", base64Part.imageBase64());

        ContentPart urlPart = ContentPart.imageUrl("https://example.com/shot.jpg");
        assertTrue(urlPart.isImage());
        assertEquals("https://example.com/shot.jpg", urlPart.imageUrl());
    }

    @Test
    void shouldSupportMultimodalUserMessage() {
        ChatMessage textOnly = ChatMessage.user("普通文本消息");
        assertFalse(textOnly.hasContentParts());
        assertFalse(textOnly.hasImages());
        assertEquals("普通文本消息", textOnly.content());

        List<ContentPart> parts = List.of(
                ContentPart.text("请分析如下架构图："),
                ContentPart.imageBase64("aW1hZ2VkYXRh", "image/png")
        );
        ChatMessage multimodalMsg = ChatMessage.user(parts);
        assertTrue(multimodalMsg.hasContentParts());
        assertTrue(multimodalMsg.hasImages());
        assertEquals(2, multimodalMsg.contentParts().size());
        assertTrue(multimodalMsg.content().contains("请分析如下架构图："));
    }

    @Test
    void shouldMaintainBackwardsCompatibilityForLegacyConstructors() {
        ChatMessage legacyMsg = new ChatMessage(ChatMessage.Role.USER, "纯文本消息");
        assertNotNull(legacyMsg.contentParts());
        assertTrue(legacyMsg.contentParts().isEmpty());
        assertFalse(legacyMsg.hasContentParts());
        assertFalse(legacyMsg.hasImages());

        ChatMessage assistantMsg = ChatMessage.assistant("模型回答");
        assertEquals(ChatMessage.Role.ASSISTANT, assistantMsg.role());
        assertFalse(assistantMsg.hasImages());
    }
}
