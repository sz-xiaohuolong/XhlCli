package com.xhlcli.mcp.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resolved configuration definition for an MCP Server.
 */
public record McpServerConfig(
        String name,
        McpTransportType transportType,
        String command,
        List<String> args,
        Map<String, String> env,
        String url,
        Map<String, String> headers,
        boolean disabled,
        boolean trustedReadOnly,
        String sourcePath
) {
    public McpServerConfig {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(transportType, "transportType");
        args = args != null ? List.copyOf(args) : List.of();
        env = env != null ? Map.copyOf(env) : Map.of();
        headers = headers != null ? Map.copyOf(headers) : Map.of();
    }

    public static McpServerConfig stdio(String name, String command, List<String> args, Map<String, String> env, boolean trustedReadOnly, String sourcePath) {
        return new McpServerConfig(name, McpTransportType.STDIO, command, args, env, null, Map.of(), false, trustedReadOnly, sourcePath);
    }

    public static McpServerConfig http(String name, String url, Map<String, String> headers, boolean trustedReadOnly, String sourcePath) {
        return new McpServerConfig(name, McpTransportType.HTTP, null, List.of(), Map.of(), url, headers, false, trustedReadOnly, sourcePath);
    }
}
