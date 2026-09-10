package com.xhlcli.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Embedding 客户端，支持 Ollama 本地模型、OpenAI/智谱 兼容 API 以及离线 Fake 测试模式
 */
public class EmbeddingClient {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private final String provider;
    private final String model;
    private final String baseUrl;
    private final String apiKey;

    public EmbeddingClient() {
        this.provider = getConfig("xhlcli.embedding.provider", "EMBEDDING_PROVIDER", "fake");
        this.model = getConfig("xhlcli.embedding.model", "EMBEDDING_MODEL", defaultModel(provider));
        this.baseUrl = getConfig("xhlcli.embedding.base.url", "EMBEDDING_BASE_URL", inferDefaultUrl(provider));
        this.apiKey = getConfig("xhlcli.embedding.api.key", "EMBEDDING_API_KEY", "");
    }

    public EmbeddingClient(String provider, String model, String baseUrl, String apiKey) {
        this.provider = provider != null ? provider : "fake";
        this.model = model != null ? model : defaultModel(this.provider);
        this.baseUrl = baseUrl != null ? baseUrl : inferDefaultUrl(this.provider);
        this.apiKey = apiKey != null ? apiKey : "";
    }

    public static EmbeddingClient fake() {
        return new EmbeddingClient("fake", "fake-model", "", "");
    }

    // 安全截断长度（适配不同模型上下文）
    private static final int MAX_INPUT_CHARS = 2000;

    /**
     * 获取文本的向量表示
     */
    public float[] embed(String text) throws IOException {
        if (text == null || text.isEmpty()) {
            return new float[0];
        }

        // 截断过长文本，防止 API 报错
        String input = text.length() > MAX_INPUT_CHARS
                ? text.substring(0, MAX_INPUT_CHARS)
                : text;

        return switch (provider.toLowerCase()) {
            case "fake", "mock" -> embedFake(input);
            case "ollama" -> embedOllama(input);
            case "openai", "zhipu", "glm" -> embedOpenAICompatible(input);
            default -> embedFake(input);
        };
    }

    /**
     * 确定性离线假向量生成器（用于纯离线单元测试和无外部服务时的降级）
     */
    public static float[] embedFake(String text) {
        int dim = 128;
        float[] vec = new float[dim];
        if (text == null || text.isEmpty()) {
            return vec;
        }

        // 提取字符与 n-gram 特征，使得相似文本有更高内积
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            int idx1 = (b * 31 + (i % 11) * 17) % dim;
            vec[Math.abs(idx1)] += 1.0f;
            if (i + 1 < bytes.length) {
                int b2 = bytes[i + 1] & 0xFF;
                int idx2 = ((b * 31 + b2) * 13) % dim;
                vec[Math.abs(idx2)] += 1.5f;
            }
        }

        // L2 归一化
        float sumSquares = 0.0f;
        for (float v : vec) {
            sumSquares += v * v;
        }
        if (sumSquares > 0.0f) {
            float norm = (float) Math.sqrt(sumSquares);
            for (int i = 0; i < dim; i++) {
                vec[i] /= norm;
            }
        }
        return vec;
    }

    private float[] embedOllama(String text) throws IOException {
        String url = baseUrl + "/api/embeddings";

        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("prompt", text);

        String responseBody = postJson(url, requestBody.toString(), false);
        JsonNode root = mapper.readTree(responseBody);
        JsonNode embeddingNode = root.path("embedding");

        if (!embeddingNode.isArray()) {
            throw new IOException("Ollama 返回的 embedding 格式不正确: " + responseBody);
        }

        float[] embedding = new float[embeddingNode.size()];
        for (int i = 0; i < embeddingNode.size(); i++) {
            embedding[i] = (float) embeddingNode.get(i).asDouble();
        }
        return embedding;
    }

    private float[] embedOpenAICompatible(String text) throws IOException {
        String url = baseUrl + "/embeddings";

        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("input", text);

        String responseBody = postJson(url, requestBody.toString(), true);
        JsonNode root = mapper.readTree(responseBody);
        JsonNode data = root.path("data");

        if (!data.isArray() || data.isEmpty()) {
            throw new IOException("API 返回的 embedding 格式不正确: " + responseBody);
        }

        JsonNode embeddingNode = data.get(0).path("embedding");
        float[] embedding = new float[embeddingNode.size()];
        for (int i = 0; i < embeddingNode.size(); i++) {
            embedding[i] = (float) embeddingNode.get(i).asDouble();
        }
        return embedding;
    }

    private String postJson(String url, String jsonBody, boolean useAuth) throws IOException {
        RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(body);

        if (useAuth && apiKey != null && !apiKey.isEmpty()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        try (Response response = HTTP_CLIENT.newCall(builder.build()).execute()) {
            ResponseBody responseBody = response.body();
            if (!response.isSuccessful()) {
                String error = responseBody != null ? responseBody.string() : "无响应";
                throw new IOException("Embedding API 请求失败 [" + response.code() + "]: " + error);
            }
            if (responseBody == null) {
                throw new IOException("Embedding API 返回空响应体");
            }
            return responseBody.string();
        }
    }

    private static String defaultModel(String provider) {
        return switch (provider.toLowerCase()) {
            case "ollama" -> "nomic-embed-text:latest";
            case "openai" -> "text-embedding-3-small";
            case "zhipu", "glm" -> "embedding-3";
            default -> "fake-embed-128";
        };
    }

    private static String inferDefaultUrl(String provider) {
        return switch (provider.toLowerCase()) {
            case "ollama" -> "http://localhost:11434";
            case "zhipu", "glm" -> "https://open.bigmodel.cn/api/paas/v4";
            case "openai" -> "https://api.openai.com/v1";
            default -> "";
        };
    }

    private static String getConfig(String propertyKey, String envKey, String defaultValue) {
        String prop = System.getProperty(propertyKey);
        if (prop != null && !prop.isBlank()) {
            return prop.trim();
        }
        String env = System.getenv(envKey);
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return defaultValue;
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public String getBaseUrl() {
        return baseUrl;
    }
}
