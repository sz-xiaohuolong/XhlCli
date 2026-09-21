package com.xhlcli.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * Standard JSON-RPC 2.0 Request.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcRequest(
        String jsonrpc,
        Object id,
        String method,
        JsonNode params
) {
    public JsonRpcRequest {
        jsonrpc = jsonrpc != null ? jsonrpc : McpConstants.JSONRPC_VERSION;
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(method, "method");
    }

    public static JsonRpcRequest of(Object id, String method, JsonNode params) {
        return new JsonRpcRequest(McpConstants.JSONRPC_VERSION, id, method, params);
    }
}
