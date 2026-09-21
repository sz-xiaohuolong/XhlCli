package com.xhlcli.mcp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.mcp.model.*;
import com.xhlcli.mcp.protocol.*;
import com.xhlcli.mcp.transport.McpTransport;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Protocol client managing the MCP handshake, tools, resources, and notification callbacks.
 */
public final class McpClient implements Closeable {

    private final String serverName;
    private final McpTransport transport;
    private final ObjectMapper mapper;
    private final AtomicLong requestIdSeq = new AtomicLong(1);

    private volatile boolean initialized = false;
    private volatile JsonNode serverCapabilities;
    private volatile JsonNode serverInfo;

    private final List<Runnable> toolsListChangedListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> resourcesListChangedListeners = new CopyOnWriteArrayList<>();

    public McpClient(String serverName, McpTransport transport, ObjectMapper mapper) {
        this.serverName = Objects.requireNonNull(serverName, "serverName");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.mapper = mapper != null ? mapper : new ObjectMapper();
        this.transport.setNotificationListener(this::handleInboundNotification);
    }

    public String getServerName() {
        return serverName;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public JsonNode getServerCapabilities() {
        return serverCapabilities;
    }

    public JsonNode getServerInfo() {
        return serverInfo;
    }

    public void onToolsListChanged(Runnable listener) {
        if (listener != null) {
            toolsListChangedListeners.add(listener);
        }
    }

    public void onResourcesListChanged(Runnable listener) {
        if (listener != null) {
            resourcesListChangedListeners.add(listener);
        }
    }

    /**
     * Executes the standard MCP initialization handshake.
     */
    public synchronized void initialize(Duration timeout) throws IOException, McpException {
        if (initialized) {
            return;
        }

        transport.start();

        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", McpConstants.PROTOCOL_VERSION);

        ObjectNode capabilities = params.putObject("capabilities");
        capabilities.putObject("roots").put("listChanged", true);

        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", McpConstants.CLIENT_NAME);
        clientInfo.put("version", McpConstants.CLIENT_VERSION);

        JsonRpcRequest req = JsonRpcRequest.of(requestIdSeq.getAndIncrement(), McpConstants.METHOD_INITIALIZE, params);

        JsonRpcResponse resp = awaitResponse(transport.sendRequest(req, null), timeout);
        if (resp.isError()) {
            throw new McpException("Initialization failed: " + resp.error().message());
        }

        JsonNode result = resp.result();
        if (result != null) {
            this.serverCapabilities = result.path("capabilities");
            this.serverInfo = result.path("serverInfo");
        }

        // Send notifications/initialized
        transport.sendNotification(JsonRpcNotification.of(McpConstants.METHOD_NOTIFICATIONS_INITIALIZED, mapper.createObjectNode()));
        this.initialized = true;
    }

    /**
     * Queries available tools from the server via `tools/list`.
     */
    public List<McpToolDefinition> listTools(Duration timeout) throws McpException {
        ensureInitialized();
        JsonRpcRequest req = JsonRpcRequest.of(requestIdSeq.getAndIncrement(), McpConstants.METHOD_TOOLS_LIST, mapper.createObjectNode());
        JsonRpcResponse resp = awaitResponse(transport.sendRequest(req, null), timeout);
        if (resp.isError()) {
            throw new McpException("tools/list failed: " + resp.error().message());
        }

        List<McpToolDefinition> tools = new ArrayList<>();
        JsonNode toolsArray = resp.result() != null ? resp.result().path("tools") : null;
        if (toolsArray != null && toolsArray.isArray()) {
            for (JsonNode toolNode : toolsArray) {
                String name = toolNode.path("name").asText();
                String description = toolNode.path("description").asText("");
                JsonNode inputSchema = toolNode.path("inputSchema");
                tools.add(new McpToolDefinition(name, description, inputSchema));
            }
        }
        return tools;
    }

    /**
     * Calls a tool via `tools/call`.
     */
    public McpCallResult callTool(String toolName, JsonNode arguments, CancellationToken cancellationToken, Duration timeout) throws McpException {
        ensureInitialized();
        ObjectNode params = mapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", arguments != null ? arguments : mapper.createObjectNode());

        JsonRpcRequest req = JsonRpcRequest.of(requestIdSeq.getAndIncrement(), McpConstants.METHOD_TOOLS_CALL, params);
        JsonRpcResponse resp = awaitResponse(transport.sendRequest(req, cancellationToken), timeout);

        if (resp.isError()) {
            return McpCallResult.failure("MCP Error (" + resp.error().code() + "): " + resp.error().message());
        }

        JsonNode result = resp.result();
        if (result == null) {
            return McpCallResult.success(List.of());
        }

        boolean isError = result.path("isError").asBoolean(false);
        List<McpContent> contents = new ArrayList<>();
        JsonNode contentArray = result.path("content");
        if (contentArray.isArray()) {
            for (JsonNode item : contentArray) {
                String type = item.path("type").asText("text");
                if ("text".equals(type)) {
                    contents.add(McpContent.text(item.path("text").asText("")));
                } else if ("image".equals(type)) {
                    String data = item.path("data").asText("");
                    String mimeType = item.path("mimeType").asText("image/png");
                    contents.add(McpContent.image(data, mimeType));
                } else if ("resource".equals(type)) {
                    JsonNode resNode = item.path("resource");
                    McpResource resource = new McpResource(
                            resNode.path("uri").asText(""),
                            resNode.path("name").asText(""),
                            resNode.path("description").asText(""),
                            resNode.path("mimeType").asText("text/plain")
                    );
                    contents.add(McpContent.resource(resource));
                }
            }
        }

        return new McpCallResult(contents, isError);
    }

    /**
     * Lists available resources from the server via `resources/list`.
     */
    public List<McpResource> listResources(Duration timeout) throws McpException {
        ensureInitialized();
        JsonRpcRequest req = JsonRpcRequest.of(requestIdSeq.getAndIncrement(), McpConstants.METHOD_RESOURCES_LIST, mapper.createObjectNode());
        JsonRpcResponse resp = awaitResponse(transport.sendRequest(req, null), timeout);
        if (resp.isError()) {
            throw new McpException("resources/list failed: " + resp.error().message());
        }

        List<McpResource> resources = new ArrayList<>();
        JsonNode resArray = resp.result() != null ? resp.result().path("resources") : null;
        if (resArray != null && resArray.isArray()) {
            for (JsonNode node : resArray) {
                String uri = node.path("uri").asText();
                String name = node.path("name").asText(uri);
                String description = node.path("description").asText("");
                String mimeType = node.path("mimeType").asText("text/plain");
                resources.add(new McpResource(uri, name, description, mimeType));
            }
        }
        return resources;
    }

    /**
     * Reads a specific resource by URI via `resources/read`.
     */
    public List<McpResourceContent> readResource(String uri, CancellationToken cancellationToken, Duration timeout) throws McpException {
        ensureInitialized();
        ObjectNode params = mapper.createObjectNode();
        params.put("uri", uri);

        JsonRpcRequest req = JsonRpcRequest.of(requestIdSeq.getAndIncrement(), McpConstants.METHOD_RESOURCES_READ, params);
        JsonRpcResponse resp = awaitResponse(transport.sendRequest(req, cancellationToken), timeout);
        if (resp.isError()) {
            throw new McpException("resources/read failed: " + resp.error().message());
        }

        List<McpResourceContent> contents = new ArrayList<>();
        JsonNode contentsArray = resp.result() != null ? resp.result().path("contents") : null;
        if (contentsArray != null && contentsArray.isArray()) {
            for (JsonNode node : contentsArray) {
                String itemUri = node.path("uri").asText(uri);
                String mimeType = node.path("mimeType").asText("text/plain");
                if (node.has("blob")) {
                    byte[] blob = Base64.getDecoder().decode(node.path("blob").asText(""));
                    contents.add(McpResourceContent.binary(itemUri, mimeType, blob));
                } else {
                    String text = node.path("text").asText("");
                    contents.add(McpResourceContent.text(itemUri, mimeType, text));
                }
            }
        }
        return contents;
    }

    private void handleInboundNotification(JsonRpcNotification notification) {
        if (notification == null) {
            return;
        }
        String method = notification.method();
        if (McpConstants.NOTIFICATION_TOOLS_LIST_CHANGED.equals(method)) {
            for (Runnable listener : toolsListChangedListeners) {
                try {
                    listener.run();
                } catch (Exception ignored) {}
            }
        } else if (McpConstants.NOTIFICATION_RESOURCES_LIST_CHANGED.equals(method)) {
            for (Runnable listener : resourcesListChangedListeners) {
                try {
                    listener.run();
                } catch (Exception ignored) {}
            }
        }
    }

    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("MCP Client for " + serverName + " is not initialized yet");
        }
    }

    private JsonRpcResponse awaitResponse(CompletableFuture<JsonRpcResponse> future, Duration timeout) {
        try {
            long waitMillis = timeout != null ? timeout.toMillis() : 15000;
            return future.get(waitMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            throw new McpException("MCP request timed out after " + (timeout != null ? timeout.toSeconds() : 15) + "s", te);
        } catch (ExecutionException ee) {
            throw new McpException("MCP transport execution error: " + ee.getCause().getMessage(), ee.getCause());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new McpException("MCP request interrupted", ie);
        }
    }

    @Override
    public void close() throws IOException {
        transport.close();
    }
}
