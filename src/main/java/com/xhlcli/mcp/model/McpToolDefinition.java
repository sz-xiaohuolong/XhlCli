package com.xhlcli.mcp.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * Metadata definition of a tool provided by an MCP Server.
 */
public record McpToolDefinition(
        String name,
        String description,
        JsonNode inputSchema
) {
    public McpToolDefinition {
        Objects.requireNonNull(name, "name");
        description = description != null ? description : "";
    }
}
