package com.xhlcli.mcp.client;

/**
 * Exception thrown during MCP protocol, transport, or execution errors.
 */
public class McpException extends RuntimeException {
    public McpException(String message) {
        super(message);
    }

    public McpException(String message, Throwable cause) {
        super(message, cause);
    }
}
