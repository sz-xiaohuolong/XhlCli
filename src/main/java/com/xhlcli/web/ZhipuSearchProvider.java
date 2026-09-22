package com.xhlcli.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 智谱开放平台 Web Search Provider。
 */
public class ZhipuSearchProvider implements SearchProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ENDPOINT = "https://open.bigmodel.cn/api/paas/v4/web_search";
    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");
    private static final Set<String> ALLOWED_ENGINES = Set.of(
            "search_std", "search_pro", "search_pro_sogou", "search_pro_quark");
    private static final String DEFAULT_ENGINE = "search_std";

    private final String apiKey;
    private final String searchEngine;
    private final OkHttpClient httpClient;

    public ZhipuSearchProvider(String apiKey) {
        this(apiKey, DEFAULT_ENGINE);
    }

    public ZhipuSearchProvider(String apiKey, String searchEngine) {
        this(apiKey, searchEngine, new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build());
    }

    ZhipuSearchProvider(String apiKey, String searchEngine, OkHttpClient httpClient) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.searchEngine = normalizeEngine(searchEngine);
        this.httpClient = httpClient;
    }

    private static String normalizeEngine(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_ENGINE;
        }
        String trimmed = raw.trim();
        return ALLOWED_ENGINES.contains(trimmed) ? trimmed : DEFAULT_ENGINE;
    }

    @Override
    public String name() {
        return "zhipu";
    }

    @Override
    public boolean isReady() {
        return !apiKey.isBlank();
    }

    @Override
    public String unavailableHint() {
        return "智谱搜索未配置 API Key。请在环境变量或 .env 中设置 ZHIPU_API_KEY。";
    }

    @Override
    public List<SearchResult> search(String query, int topK) throws IOException {
        if (!isReady()) {
            throw new IOException(unavailableHint());
        }
        int maxResults = topK > 0 ? Math.min(topK, 10) : 5;

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("query", query);
        payload.put("search_engine", searchEngine);
        payload.put("num", maxResults);

        Request request = new Request.Builder()
                .url(ENDPOINT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("User-Agent", "xhlcli-web-search/1.0")
                .post(RequestBody.create(payload.toString(), JSON_MEDIA))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                if (response.code() == 401 || response.code() == 403) {
                    throw new IOException("智谱 API Key 无效或权限不足 (HTTP " + response.code() + ")");
                }
                throw new IOException("智谱搜索请求失败 (HTTP " + response.code() + ")");
            }
            String body = response.body() == null ? "" : response.body().string();
            return parse(body, maxResults);
        }
    }

    List<SearchResult> parse(String json, int maxResults) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        JsonNode searchResultNode = root.path("search_result");
        if (searchResultNode.isMissingNode() || !searchResultNode.isArray()) {
            searchResultNode = root.path("results");
        }
        List<SearchResult> list = new ArrayList<>();
        if (searchResultNode.isArray()) {
            int position = 0;
            for (JsonNode node : searchResultNode) {
                if (position >= maxResults) {
                    break;
                }
                String title = node.path("title").asText("");
                String link = node.path("link").asText("");
                if (link.isBlank()) link = node.path("url").asText("");
                String content = node.path("content").asText("");
                if (content.isBlank()) content = node.path("snippet").asText("");
                String date = node.path("media").asText(null);
                if (date == null) date = node.path("publish_date").asText(null);
                if (title.isBlank() && content.isBlank()) {
                    continue;
                }
                position++;
                list.add(SearchResult.of(position, title, link, content, date));
            }
        }
        return list;
    }
}
