package com.xhlcli.web;

import okhttp3.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 免 API Key 零配置 DuckDuckGo HTML 搜索 Provider。
 */
public class DuckDuckGoSearchProvider implements SearchProvider {

    private static final String ENDPOINT = "https://html.duckduckgo.com/html/";

    private final OkHttpClient httpClient;

    public DuckDuckGoSearchProvider() {
        this(new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build());
    }

    DuckDuckGoSearchProvider(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public String name() {
        return "duckduckgo";
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public String unavailableHint() {
        return "DuckDuckGo 搜索引擎可用。";
    }

    @Override
    public List<SearchResult> search(String query, int topK) throws IOException {
        int maxResults = topK > 0 ? Math.min(topK, 10) : 5;

        RequestBody formBody = new FormBody.Builder()
                .add("q", query)
                .add("b", "")
                .add("kl", "wt-wt")
                .build();

        Request request = new Request.Builder()
                .url(ENDPOINT)
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .post(formBody)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("DuckDuckGo 搜索失败 (HTTP " + response.code() + ")");
            }
            String html = response.body() == null ? "" : response.body().string();
            return parse(html, maxResults);
        }
    }

    List<SearchResult> parse(String html, int maxResults) {
        Document doc = Jsoup.parse(html);
        Elements results = doc.select(".results .result");
        List<SearchResult> list = new ArrayList<>();
        int position = 0;
        for (Element result : results) {
            if (position >= maxResults) {
                break;
            }
            Element linkEl = result.selectFirst(".result__title a.result__url, .result__title a");
            if (linkEl == null) continue;

            String title = linkEl.text().trim();
            String rawLink = linkEl.attr("href").trim();
            String link = cleanDdgUrl(rawLink);
            Element snippetEl = result.selectFirst(".result__snippet");
            String snippet = snippetEl == null ? "" : snippetEl.text().trim();

            if (title.isBlank() && snippet.isBlank()) continue;

            position++;
            list.add(SearchResult.of(position, title, link, snippet));
        }
        return list;
    }

    private String cleanDdgUrl(String rawUrl) {
        if (rawUrl.contains("uddg=")) {
            int start = rawUrl.indexOf("uddg=") + 5;
            int end = rawUrl.indexOf("&", start);
            String encoded = end > start ? rawUrl.substring(start, end) : rawUrl.substring(start);
            try {
                return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
        }
        return rawUrl;
    }
}
