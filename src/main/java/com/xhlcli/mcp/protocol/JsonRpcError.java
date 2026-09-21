package com.xhlcli.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Standard JSON-RPC 2.0 Error object.
 */
public record JsonRpcError(
        int code,
        String message,
        JsonNode data
) {
    public JsonRpcError {
        message = message != null ? message : "Unknown error";
    }

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;
}
