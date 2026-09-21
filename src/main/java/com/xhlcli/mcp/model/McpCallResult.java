package com.xhlcli.mcp.model;

import java.util.List;

/**
 * Execution result of calling a tool on an MCP Server.
 */
public record McpCallResult(
        List<McpContent> contents,
        boolean isError
) {
    public McpCallResult {
        contents = contents != null ? List.copyOf(contents) : List.of();
    }

    public static McpCallResult success(List<McpContent> contents) {
        return new McpCallResult(contents, false);
    }

    public static McpCallResult failure(String errorMessage) {
        return new McpCallResult(List.of(McpContent.text(errorMessage)), true);
    }
}
