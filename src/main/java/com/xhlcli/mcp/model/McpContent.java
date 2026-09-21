package com.xhlcli.mcp.model;

import java.util.Objects;

/**
 * An item in the content array of an MCP tool call or resource result.
 */
public record McpContent(
        String type,
        String text,
        String data,
        String mimeType,
        McpResource resource
) {
    public McpContent {
        Objects.requireNonNull(type, "type");
    }

    public static McpContent text(String text) {
        return new McpContent("text", text, null, null, null);
    }

    public static McpContent image(String data, String mimeType) {
        return new McpContent("image", null, data, mimeType, null);
    }

    public static McpContent resource(McpResource resource) {
        return new McpContent("resource", null, null, null, resource);
    }
}
