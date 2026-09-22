package com.xhlcli.browser;

/**
 * 浏览器标签页元数据。
 */
public record BrowserTabInfo(
        String id,
        String title,
        String url,
        String type
) {
}
