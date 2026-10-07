package com.xhlcli.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.config.ChatConfig;
import com.xhlcli.config.LogLevel;
import com.xhlcli.model.ChatMessage;
import com.xhlcli.model.ContentPart;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okio.Buffer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultimodalClientSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final DiagnosticSink diagnostics = DiagnosticSink.NO_OP;
    private final OkHttpClient httpClient = new OkHttpClient();

    private ChatConfig createConfig(String model) {
        return new ChatConfig(
                "test-api-key",
                model,
                URI.create("https://api.example.com/v1"),
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                Duration.ofSeconds(60),
                LogLevel.INFO,
                Map.of()
        );
    }

    private JsonNode extractRequestBody(Request request) throws IOException {
        assertNotNull(request.body());
        Buffer buffer = new Buffer();
        request.body().writeTo(buffer);
        return mapper.readTree(buffer.readUtf8());
    }

    @Test
    void openAiClientSerializesMultimodalContentArrayWhenSupported() throws Exception {
        ChatConfig config = createConfig("gpt-4o");
        OpenAiClient client = new OpenAiClient(config, httpClient, mapper, Thread::sleep, diagnostics);

        ChatMessage msg = ChatMessage.user(List.of(
                ContentPart.text("请分析这张图"),
                ContentPart.imageBase64("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY44YAAAAASUVORK5CYII=", "image/png")
        ));

        Request request = client.buildRequest(List.of(msg), List.of());
        JsonNode json = extractRequestBody(request);

        JsonNode messages = json.get("messages");
        assertNotNull(messages);
        assertEquals(1, messages.size());

        JsonNode firstMsg = messages.get(0);
        assertEquals("user", firstMsg.get("role").asText());

        JsonNode content = firstMsg.get("content");
        assertTrue(content.isArray(), "Content should be array for multimodal messages");
        assertEquals(2, content.size());

        JsonNode textPart = content.get(0);
        assertEquals("text", textPart.get("type").asText());
        assertEquals("请分析这张图", textPart.get("text").asText());

        JsonNode imgPart = content.get(1);
        assertEquals("image_url", imgPart.get("type").asText());
        assertEquals("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY44YAAAAASUVORK5CYII=",
                imgPart.get("image_url").get("url").asText());
    }

    @Test
    void deepSeekClientDegradesMultimodalToPlainTextDefendingAgainst400Error() throws Exception {
        ChatConfig config = createConfig("deepseek-chat");
        DeepSeekClient client = new DeepSeekClient(config, httpClient, mapper, Thread::sleep, diagnostics);

        assertFalse(client.capabilities().supportsVision(), "DeepSeek must declare no vision support");

        ChatMessage msg = ChatMessage.user(List.of(
                ContentPart.text("请分析系统架构图"),
                ContentPart.imageBase64("FAKEDATA", "image/png")
        ));

        Request request = client.buildRequest(List.of(msg), List.of());
        JsonNode json = extractRequestBody(request);

        JsonNode messages = json.get("messages");
        assertNotNull(messages);
        assertEquals(1, messages.size());

        JsonNode firstMsg = messages.get(0);
        assertEquals("user", firstMsg.get("role").asText());

        JsonNode content = firstMsg.get("content");
        assertTrue(content.isTextual(), "Content must be degraded to plain text for non-vision models");
        String text = content.asText();
        assertTrue(text.contains("请分析系统架构图"));
        assertTrue(text.contains("当前 provider/model 不支持图片附件，已省略 1 张"));
        assertFalse(text.contains("FAKEDATA"), "Base64 data must never be sent in payload");
        assertFalse(json.toString().contains("image_url"), "No image_url block should exist in DeepSeek request");
    }

    @Test
    void anthropicClientSerializesBase64SourceBlockForVisionModels() throws Exception {
        AnthropicClaudeClient client = new AnthropicClaudeClient(
                "test-api-key",
                "claude-3-5-sonnet",
                URI.create("https://api.anthropic.com"),
                ModelCapabilities.claudeDefault(),
                httpClient,
                mapper,
                Thread::sleep,
                diagnostics
        );

        assertTrue(client.capabilities().supportsVision());

        ChatMessage msg = ChatMessage.user(List.of(
                ContentPart.text("What is this UI?"),
                ContentPart.imageBase64("iVBORw0KGgo=", "image/png")
        ));

        Request request = client.buildRequest(List.of(msg), List.of());
        JsonNode json = extractRequestBody(request);

        JsonNode messages = json.get("messages");
        assertNotNull(messages);
        assertEquals(1, messages.size());

        JsonNode firstMsg = messages.get(0);
        assertEquals("user", firstMsg.get("role").asText());

        JsonNode content = firstMsg.get("content");
        assertTrue(content.isArray(), "Anthropic content should be array for multimodal messages");
        assertEquals(2, content.size());

        JsonNode textPart = content.get(0);
        assertEquals("text", textPart.get("type").asText());
        assertEquals("What is this UI?", textPart.get("text").asText());

        JsonNode imagePart = content.get(1);
        assertEquals("image", imagePart.get("type").asText());
        JsonNode source = imagePart.get("source");
        assertEquals("base64", source.get("type").asText());
        assertEquals("image/png", source.get("media_type").asText());
        assertEquals("iVBORw0KGgo=", source.get("data").asText());
    }

    @Test
    void anthropicClientDegradesWhenConfiguredWithoutVision() throws Exception {
        AnthropicClaudeClient client = new AnthropicClaudeClient(
                "test-api-key",
                "claude-text-only",
                URI.create("https://api.anthropic.com"),
                ModelCapabilities.textOnly(128_000),
                httpClient,
                mapper,
                Thread::sleep,
                diagnostics
        );

        assertFalse(client.capabilities().supportsVision());

        ChatMessage msg = ChatMessage.user(List.of(
                ContentPart.text("Analyze code"),
                ContentPart.imageBase64("FAKEDATA", "image/png")
        ));

        Request request = client.buildRequest(List.of(msg), List.of());
        JsonNode json = extractRequestBody(request);

        JsonNode firstMsg = json.get("messages").get(0);
        JsonNode content = firstMsg.get("content");
        assertTrue(content.isTextual());
        String text = content.asText();
        assertTrue(text.contains("Analyze code"));
        assertTrue(text.contains("当前 provider/model 不支持图片附件，已省略 1 张"));
        assertFalse(json.toString().contains("image"), "No image block allowed");
    }
}
