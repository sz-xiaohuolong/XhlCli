package com.xhlcli.mcp.model;

import java.util.Objects;

/**
 * Content payload of a read resource.
 */
public record McpResourceContent(
        String uri,
        String mimeType,
        String text,
        byte[] blob
) {
    public McpResourceContent {
        Objects.requireNonNull(uri, "uri");
        mimeType = mimeType != null ? mimeType : "text/plain";
    }

    public static McpResourceContent text(String uri, String mimeType, String text) {
        return new McpResourceContent(uri, mimeType, text, null);
    }

    public static McpResourceContent binary(String uri, String mimeType, byte[] blob) {
        return new McpResourceContent(uri, mimeType, null, blob);
    }
}
