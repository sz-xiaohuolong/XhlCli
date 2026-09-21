package com.xhlcli.mcp.model;

/**
 * Lifecycle status of an MCP Server instance.
 */
public enum McpServerStatus {
    DISABLED,
    STARTING,
    READY,
    ERROR,
    STOPPED
}
