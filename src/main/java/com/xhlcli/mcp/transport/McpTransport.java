package com.xhlcli.mcp.transport;

import com.xhlcli.llm.CancellationToken;
import com.xhlcli.mcp.protocol.JsonRpcNotification;
import com.xhlcli.mcp.protocol.JsonRpcRequest;
import com.xhlcli.mcp.protocol.JsonRpcResponse;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Transport layer interface connecting XhlCLI to an external MCP Server.
 */
public interface McpTransport extends Closeable {
    /**
     * Starts the transport connection (spawns process or validates network connection).
     */
    void start() throws IOException;

    /**
     * Sends a JSON-RPC request asynchronously and returns a future for its response.
     */
    CompletableFuture<JsonRpcResponse> sendRequest(JsonRpcRequest request, CancellationToken cancellationToken);

    /**
     * Sends a JSON-RPC notification (no response expected).
     */
    void sendNotification(JsonRpcNotification notification) throws IOException;

    /**
     * Registers a listener to handle inbound server-to-client notifications.
     */
    void setNotificationListener(Consumer<JsonRpcNotification> listener);

    /**
     * Checks if the transport connection is currently active and healthy.
     */
    boolean isAlive();

    /**
     * Retrieves recent diagnostic logs (e.g. captured stderr or network logs).
     */
    List<String> getRecentLogs();
}
