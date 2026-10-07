package com.xhlcli.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.config.ChatConfig;
import com.xhlcli.config.LogLevel;
import com.xhlcli.context.TokenBudget;
import com.xhlcli.llm.DeepSeekClient;
import com.xhlcli.llm.DiagnosticSink;
import com.xhlcli.llm.OpenAiClient;
import com.xhlcli.model.ChatMessage;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okio.Buffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MultimodalGoldenTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final DiagnosticSink diagnostics = DiagnosticSink.NO_OP;
    private final OkHttpClient httpClient = new OkHttpClient();

    private Path samplePng;

    @BeforeEach
    void setUp() throws IOException {
        samplePng = tempDir.resolve("arch-diagram.png");
        BufferedImage img = new BufferedImage(100, 80, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(255, 0, 0, 128)); // 半透明红色
        g.fillRect(0, 0, 100, 80);
        g.dispose();
        ImageIO.write(img, "png", samplePng.toFile());
    }

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
    void goldenEndToEndVisionWorkflowWithOpenAi() throws Exception {
        // 1. 用户终端输入带路径引用宏
        String userInput = "请帮我重构这个模块的架构设计：@image:" + samplePng.toString();

        // 2. 宏解析与图片预处理
        ChatMessage userMessage = ImageReferenceParser.userMessage(userInput, tempDir);
        assertTrue(userMessage.hasImages(), "Must recognize and attach image");
        assertEquals(1, userMessage.imagePartCount());

        // 3. Token 预算核算
        int estimatedTokens = TokenBudget.estimateTokens(userMessage);
        assertTrue(estimatedTokens >= 1000, "Estimated tokens must include 1000 tokens for image");

        // 4. 序列化为 OpenAI 视觉模型调用
        OpenAiClient openAiClient = new OpenAiClient(createConfig("gpt-4o"), diagnostics);
        assertTrue(openAiClient.capabilities().supportsVision());

        Request request = openAiClient.buildRequest(List.of(userMessage), List.of());
        JsonNode json = extractRequestBody(request);

        JsonNode messages = json.get("messages");
        assertNotNull(messages);
        assertEquals(1, messages.size());

        JsonNode firstMsg = messages.get(0);
        JsonNode content = firstMsg.get("content");
        assertTrue(content.isArray(), "Vision model must receive array content");

        boolean hasImageBlock = false;
        boolean hasTextPrompt = false;
        for (JsonNode part : content) {
            String type = part.path("type").asText();
            if ("text".equals(type)) {
                String text = part.path("text").asText();
                if (text.contains("重构这个模块的架构设计") && text.contains("Image: source:")) {
                    hasTextPrompt = true;
                }
            } else if ("image_url".equals(type)) {
                String url = part.path("image_url").path("url").asText();
                if (url.startsWith("data:image/png;base64,")) {
                    hasImageBlock = true;
                }
            }
        }
        assertTrue(hasTextPrompt, "Must contain prompt and coordinate metadata");
        assertTrue(hasImageBlock, "Must contain image_url block with Base64");
    }

    @Test
    void goldenEndToEndVisionDefenseWorkflowWithDeepSeek() throws Exception {
        // 1. 相同的输入给非视觉模型
        String userInput = "请帮我重构架构：@image:" + samplePng.toString();
        ChatMessage userMessage = ImageReferenceParser.userMessage(userInput, tempDir);

        // 2. 序列化为 DeepSeek 调用（非视觉）
        DeepSeekClient deepSeekClient = new DeepSeekClient(createConfig("deepseek-chat"), diagnostics);
        assertFalse(deepSeekClient.capabilities().supportsVision());

        Request request = deepSeekClient.buildRequest(List.of(userMessage), List.of());
        JsonNode json = extractRequestBody(request);

        JsonNode messages = json.get("messages");
        JsonNode firstMsg = messages.get(0);
        JsonNode content = firstMsg.get("content");

        // 验证：绝对降级为纯文本，杜绝 400 Bad Request
        assertTrue(content.isTextual(), "Non-vision model must receive string content");
        String text = content.asText();
        assertTrue(text.contains("重构架构"));
        assertTrue(text.contains("当前 provider/model 不支持图片附件，已省略 1 张"));
        assertFalse(json.toString().contains("image_url"));
    }

    @Test
    void goldenHistoricalImagePayloadPruning() {
        ChatMessage round1 = ImageReferenceParser.userMessage("第一轮图片：@image:" + samplePng.toString(), tempDir);
        assertTrue(round1.hasImages());

        int initialTokens = TokenBudget.estimateTokens(round1);
        assertTrue(initialTokens >= 1000);

        // 模拟多轮对话历史推进：修剪历史图片 Payload
        List<ChatMessage> history = new ArrayList<>(List.of(round1));
        for (int i = 0; i < history.size(); i++) {
            ChatMessage msg = history.get(i);
            if (msg.hasImages()) {
                history.set(i, msg.withoutImageContent());
            }
        }

        ChatMessage pruned = history.get(0);
        assertFalse(pruned.hasImages(), "Images must be stripped after pruning");
        assertEquals(0, pruned.imagePartCount());
        assertTrue(pruned.content().contains("已省略 1 张图片附件"));

        int prunedTokens = TokenBudget.estimateTokens(pruned);
        assertTrue(prunedTokens < 300, "Tokens should decrease drastically after pruning Base64 payload");
    }
}
