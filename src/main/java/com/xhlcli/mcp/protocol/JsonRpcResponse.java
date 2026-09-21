package com.xhlcli.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Standard JSON-RPC 2.0 Response.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcResponse(
        String jsonrpc,
        Object id,
        JsonNode result,
        JsonRpcError error
) {
    public JsonRpcResponse {
        jsonrpc = jsonrpc != null ? jsonrpc : McpConstants.JSONRPC_VERSION;
    }

    public boolean isError() {
        return error != null;
    }

    public static JsonRpcResponse success(Object id, JsonNode result) {
        return new JsonRpcResponse(McpConstants.JSONRPC_VERSION, id, result, null);
    }

    public static JsonRpcResponse error(Object id, JsonRpcError error) {
        return new JsonRpcResponse(McpConstants.JSONRPC_VERSION, id, null, error);
    }
}
