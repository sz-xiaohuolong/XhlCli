package com.xhlcli.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * SearXNG 自托管开源元搜索引擎实现。
 */
public class SearxngSearchProvider implements SearchProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final OkHttpClient httpClient;

    public SearxngSearchProvider(String baseUrl) {
        this(baseUrl, new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build());
    }

    SearxngSearchProvider(String baseUrl, OkHttpClient httpClient) {
        this.baseUrl = (baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", ""));
        this.httpClient = httpClient;
    }

    @Override
    public String name() {
        return "searxng";
    }

    @Override
    public boolean isReady() {
        return !baseUrl.isBlank() && HttpUrl.parse(baseUrl + "/search") != null;
    }

    @Override
    public String unavailableHint() {
        return "SearXNG 未配置或地址非法。请在环境变量或 .env 中设置 SEARXNG_URL=http://localhost:8888。";
    }

    @Override
    public List<SearchResult> search(String query, int topK) throws IOException {
        if (!isReady()) {
            throw new IOException(unavailableHint());
        }
        int maxResults = topK > 0 ? Math.min(topK, 10) : 5;

        HttpUrl url = HttpUrl.parse(baseUrl + "/search").newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("format", "json")
                .addQueryParameter("language", "zh")
                .build();

        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "xhlcli-web-search/1.0")
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("SearXNG 请求失败 (HTTP " + response.code() + ")");
            }
            String body = response.body() == null ? "" : response.body().string();
            return parse(body, maxResults);
        }
    }

    List<SearchResult> parse(String json, int maxResults) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        JsonNode resultsNode = root.path("results");
        List<SearchResult> results = new ArrayList<>();
        if (resultsNode.isArray()) {
            int position = 0;
            for (JsonNode node : resultsNode) {
                if (position >= maxResults) {
                    break;
                }
                String title = node.path("title").asText("");
                String link = node.path("url").asText("");
                String content = node.path("content").asText("");
                String date = node.path("publishedDate").asText(null);
                if (title.isBlank() && content.isBlank()) {
                    continue;
                }
                position++;
                results.add(SearchResult.of(position, title, link, content, date));
            }
        }
        return results;
    }
}
