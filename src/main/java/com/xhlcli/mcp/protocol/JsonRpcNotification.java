package com.xhlcli.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * Standard JSON-RPC 2.0 Notification (no id).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcNotification(
        String jsonrpc,
        String method,
        JsonNode params
) {
    public JsonRpcNotification {
        jsonrpc = jsonrpc != null ? jsonrpc : McpConstants.JSONRPC_VERSION;
        Objects.requireNonNull(method, "method");
    }

    public static JsonRpcNotification of(String method, JsonNode params) {
        return new JsonRpcNotification(McpConstants.JSONRPC_VERSION, method, params);
    }
}
