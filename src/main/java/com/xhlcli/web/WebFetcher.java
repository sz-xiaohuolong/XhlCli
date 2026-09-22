package com.xhlcli.web;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 具备 SSRF 深度防御、重定向重校验与流式截断的 HTTP 网页抓取器。
 */
public class WebFetcher {

    public static final int DEFAULT_MAX_BYTES = 5 * 1024 * 1024; // 5MB
    public static final int MAX_REDIRECTS = 5;
    private static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 XhlCLI/0.11.0";
    private static final Pattern CHARSET_PATTERN = Pattern.compile("charset=[\"']?([A-Za-z0-9_-]+)[\"']?", Pattern.CASE_INSENSITIVE);

    private final NetworkPolicy networkPolicy;
    private final HtmlExtractor htmlExtractor;
    private final int maxBytes;
    private final OkHttpClient httpClient;

    public WebFetcher() {
        this(new NetworkPolicy(), new HtmlExtractor(), DEFAULT_MAX_BYTES);
    }

    public WebFetcher(NetworkPolicy networkPolicy, HtmlExtractor htmlExtractor, int maxBytes) {
        this(networkPolicy, htmlExtractor, maxBytes, new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build());
    }

    WebFetcher(NetworkPolicy networkPolicy, HtmlExtractor htmlExtractor, int maxBytes, OkHttpClient httpClient) {
        this.networkPolicy = networkPolicy;
        this.htmlExtractor = htmlExtractor;
        this.maxBytes = maxBytes;
        this.httpClient = httpClient;
    }

    public FetchResult fetch(String url) throws IOException {
        String rateLimitError = networkPolicy.acquire();
        if (rateLimitError != null) {
            throw new IOException(rateLimitError);
        }

        String currentUrl = url;
        int redirectCount = 0;

        while (true) {
            String policyError = networkPolicy.checkUrl(currentUrl);
            if (policyError != null) {
                throw new IOException("网络访问被安全策略拦截: " + policyError);
            }

            Request request = new Request.Builder()
                    .url(currentUrl)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("User-Agent", DEFAULT_USER_AGENT)
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                int code = response.code();

                // 处理重定向
                if (code >= 300 && code < 400 && response.header("Location") != null) {
                    redirectCount++;
                    if (redirectCount > MAX_REDIRECTS) {
                        throw new IOException("重定向次数过多 (超过 " + MAX_REDIRECTS + " 次)");
                    }
                    String location = response.header("Location").trim();
                    try {
                        URI base = URI.create(currentUrl);
                        currentUrl = base.resolve(location).toString();
                    } catch (Exception e) {
                        throw new IOException("重定向 Location 格式非法: " + location);
                    }
                    continue;
                }

                if (!response.isSuccessful()) {
                    throw new IOException("HTTP " + code + " " + response.message());
                }

                ResponseBody body = response.body();
                if (body == null) {
                    throw new IOException("响应体为空");
                }

                byte[] bytes = readBounded(body.byteStream(), maxBytes);
                boolean truncated = bytes.length >= maxBytes;
                Charset charset = resolveCharset(response, bytes);
                String html = new String(bytes, charset);

                HtmlExtractor.Extracted extracted = htmlExtractor.extract(html, currentUrl);
                return FetchResult.of(url, currentUrl, code, extracted.title(), extracted.markdown(), truncated);
            }
        }
    }

    private Charset resolveCharset(Response response, byte[] contentBytes) {
        // 1. 优先 Content-Type header
        try {
            if (response.body() != null && response.body().contentType() != null) {
                Charset cs = response.body().contentType().charset();
                if (cs != null) return cs;
            }
        } catch (Exception ignored) {
        }

        // 2. 嗅探 HTML 前 2048 字节的 meta charset 声明
        try {
            int inspectLen = Math.min(contentBytes.length, 2048);
            String snippet = new String(contentBytes, 0, inspectLen, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
            Matcher matcher = CHARSET_PATTERN.matcher(snippet);
            if (matcher.find()) {
                String charsetName = matcher.group(1);
                return Charset.forName(charsetName);
            }
        } catch (Exception ignored) {
        }

        return StandardCharsets.UTF_8;
    }

    private byte[] readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int n;
        while ((n = input.read(buffer)) != -1) {
            int toWrite = Math.min(n, limit - total);
            out.write(buffer, 0, toWrite);
            total += toWrite;
            if (total >= limit) {
                break;
            }
        }
        return out.toByteArray();
    }
}
