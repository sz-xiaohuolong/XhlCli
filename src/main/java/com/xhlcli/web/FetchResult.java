package com.xhlcli.web;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 网页抓取与提取结果。
 *
 * @param url 初始请求 URL
 * @param finalUrl 重定向后最终 URL
 * @param statusCode HTTP 状态码
 * @param title 网页标题
 * @param markdown 转换提取后的正文 Markdown
 * @param truncated 是否超出长度/体积被截断
 * @param fetchDate 抓取时间（ISO-8601 或 YYYY-MM-DD）
 */
public record FetchResult(
        String url,
        String finalUrl,
        int statusCode,
        String title,
        String markdown,
        boolean truncated,
        String fetchDate) {

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withZone(ZoneOffset.UTC);

    public static FetchResult of(String url, String finalUrl, int statusCode, String title, String markdown, boolean truncated) {
        return new FetchResult(
                url,
                finalUrl == null || finalUrl.isBlank() ? url : finalUrl,
                statusCode,
                title == null ? "" : title.trim(),
                markdown == null ? "" : markdown.trim(),
                truncated,
                DATE_FORMATTER.format(Instant.now())
        );
    }
}
