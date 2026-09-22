package com.xhlcli.web;

import java.net.URI;

/**
 * 搜索结果领域模型。
 *
 * @param position 排序位次（从 1 开始）
 * @param title 网页标题
 * @param url 网页链接
 * @param snippet 网页摘要内容
 * @param source 来源域名/站点标识
 * @param publishedDate 发布或抓取日期（若有）
 */
public record SearchResult(
        int position,
        String title,
        String url,
        String snippet,
        String source,
        String publishedDate) {

    public static SearchResult of(int position, String title, String url, String snippet) {
        return of(position, title, url, snippet, null);
    }

    public static SearchResult of(int position, String title, String url, String snippet, String publishedDate) {
        return new SearchResult(
                position,
                safe(title),
                safe(url),
                safe(snippet),
                extractHost(url),
                publishedDate == null || publishedDate.isBlank() ? null : publishedDate.trim()
        );
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static String extractHost(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host;
        } catch (Exception e) {
            return "";
        }
    }
}
