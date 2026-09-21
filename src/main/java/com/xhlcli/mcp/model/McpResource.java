package com.xhlcli.mcp.model;

import java.util.Objects;

/**
 * Metadata descriptor of a resource provided by an MCP Server.
 */
public record McpResource(
        String uri,
        String name,
        String description,
        String mimeType
) {
    public McpResource {
        Objects.requireNonNull(uri, "uri");
        name = name != null ? name : uri;
        description = description != null ? description : "";
        mimeType = mimeType != null ? mimeType : "text/plain";
    }
}
