package com.xhlcli.mcp.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.hitl.ApprovalPolicy;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.mcp.client.McpClient;
import com.xhlcli.mcp.client.McpException;
import com.xhlcli.mcp.config.McpConfigLoader;
import com.xhlcli.mcp.model.*;
import com.xhlcli.mcp.tool.McpToolAdapter;
import com.xhlcli.mcp.transport.McpTransport;
import com.xhlcli.mcp.transport.StdioMcpTransport;
import com.xhlcli.mcp.transport.StreamableHttpMcpTransport;
import com.xhlcli.tool.ToolRegistry;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Manages the lifecycle of MCP Servers, coordinating startup budget, tool synchronization,
 * resource indexing, and dynamic ToolRegistry integration.
 */
public final class McpServerManager implements Closeable {

    public static final class ServerEntry {
        private final McpServerConfig config;
        private volatile McpServerStatus status;
        private volatile McpTransport transport;
        private volatile McpClient client;
        private volatile List<McpToolAdapter> tools = List.of();
        private volatile List<McpResource> resources = List.of();
        private volatile String errorMessage;

        public ServerEntry(McpServerConfig config, McpServerStatus status, String errorMessage) {
            this.config = config;
            this.status = status;
            this.errorMessage = errorMessage;
        }

        public McpServerConfig config() { return config; }
        public McpServerStatus status() { return status; }
        public McpTransport transport() { return transport; }
        public McpClient client() { return client; }
        public List<McpToolAdapter> tools() { return tools; }
        public List<McpResource> resources() { return resources; }
        public String errorMessage() { return errorMessage; }

        public List<String> getRecentLogs() {
            return transport != null ? transport.getRecentLogs() : List.of();
        }
    }

    private final Map<String, ServerEntry> servers = new ConcurrentHashMap<>();
    private final ToolRegistry toolRegistry;
    private final ObjectMapper mapper;
    private final Path workingDirectory;
    private final List<Consumer<List<com.xhlcli.model.ToolDefinition>>> toolsUpdatedListeners = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mcp-server-mgr-pool");
        t.setDaemon(true);
        return t;
    });

    public McpServerManager(ToolRegistry toolRegistry, Path workingDirectory, ObjectMapper mapper) {
        this.toolRegistry = toolRegistry;
        this.workingDirectory = workingDirectory;
        this.mapper = mapper != null ? mapper : new ObjectMapper();
    }

    public void addToolsUpdatedListener(Consumer<List<com.xhlcli.model.ToolDefinition>> listener) {
        if (listener != null) {
            toolsUpdatedListeners.add(listener);
        }
    }

    /**
     * Registers servers from configuration load result.
     */
    public void registerServers(McpConfigLoader.LoadResult loadResult) {
        if (loadResult == null) {
            return;
        }

        // Register valid servers
        for (Map.Entry<String, McpServerConfig> entry : loadResult.servers().entrySet()) {
            String name = entry.getKey();
            McpServerConfig config = entry.getValue();
            McpServerStatus status = config.disabled() ? McpServerStatus.DISABLED : McpServerStatus.STOPPED;
            servers.put(name, new ServerEntry(config, status, null));
        }

        // Register error servers
        for (Map.Entry<String, String> entry : loadResult.errors().entrySet()) {
            String name = entry.getKey();
            String error = entry.getValue();
            if (!servers.containsKey(name)) {
                McpServerConfig dummy = McpServerConfig.stdio(name, "unknown", List.of(), Map.of(), false, "unknown");
                servers.put(name, new ServerEntry(dummy, McpServerStatus.ERROR, error));
            } else {
                ServerEntry existing = servers.get(name);
                existing.status = McpServerStatus.ERROR;
                existing.errorMessage = error;
            }
        }
    }

    /**
     * Starts all non-disabled servers with a bounded startup budget.
     */
    public void startAll(Duration startupBudget) {
        List<CompletableFuture<Void>> startFutures = new ArrayList<>();

        for (Map.Entry<String, ServerEntry> entry : servers.entrySet()) {
            ServerEntry server = entry.getValue();
            if (server.status() == McpServerStatus.DISABLED || server.status() == McpServerStatus.ERROR) {
                continue;
            }

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                startServerInternal(server, Duration.ofSeconds(10));
            }, executor);
            startFutures.add(future);
        }

        if (startupBudget != null && !startupBudget.isZero() && !startFutures.isEmpty()) {
            try {
                CompletableFuture<Void> all = CompletableFuture.allOf(startFutures.toArray(new CompletableFuture[0]));
                all.get(startupBudget.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                // Startup budget exceeded, servers continue initializing in background
            } catch (Exception ignored) {}
        }
    }

    public synchronized void startServer(String serverName, Duration timeout) {
        ServerEntry server = servers.get(serverName);
        if (server == null) {
            throw new IllegalArgumentException("Unknown MCP server: " + serverName);
        }
        startServerInternal(server, timeout);
    }

    public synchronized void stopServer(String serverName) {
        ServerEntry server = servers.get(serverName);
        if (server == null) {
            throw new IllegalArgumentException("Unknown MCP server: " + serverName);
        }
        stopServerInternal(server);
        refreshRegistry();
    }

    public synchronized void restartServer(String serverName, Duration timeout) {
        stopServer(serverName);
        startServer(serverName, timeout);
    }

    public synchronized void disableServer(String serverName) {
        ServerEntry server = servers.get(serverName);
        if (server == null) {
            throw new IllegalArgumentException("Unknown MCP server: " + serverName);
        }
        stopServerInternal(server);
        server.status = McpServerStatus.DISABLED;
        refreshRegistry();
    }

    public synchronized void enableServer(String serverName, Duration timeout) {
        ServerEntry server = servers.get(serverName);
        if (server == null) {
            throw new IllegalArgumentException("Unknown MCP server: " + serverName);
        }
        server.status = McpServerStatus.STOPPED;
        startServer(serverName, timeout);
    }

    public Map<String, ServerEntry> listServers() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(servers));
    }

    public ServerEntry getServer(String serverName) {
        return servers.get(serverName);
    }

    public List<McpToolAdapter> getAllTools() {
        List<McpToolAdapter> all = new ArrayList<>();
        for (ServerEntry server : servers.values()) {
            if (server.status() == McpServerStatus.READY) {
                all.addAll(server.tools());
            }
        }
        return all;
    }

    public List<McpResource> getAllResources() {
        List<McpResource> all = new ArrayList<>();
        for (ServerEntry server : servers.values()) {
            if (server.status() == McpServerStatus.READY) {
                all.addAll(server.resources());
            }
        }
        return all;
    }

    public List<McpResourceContent> readResource(String uri, CancellationToken cancellationToken, Duration timeout) {
        for (ServerEntry server : servers.values()) {
            if (server.status() == McpServerStatus.READY && server.client() != null) {
                for (McpResource res : server.resources()) {
                    if (res.uri().equals(uri)) {
                        return server.client().readResource(uri, cancellationToken, timeout);
                    }
                }
            }
        }
        // Fallback: try on any ready server
        for (ServerEntry server : servers.values()) {
            if (server.status() == McpServerStatus.READY && server.client() != null) {
                try {
                    return server.client().readResource(uri, cancellationToken, timeout);
                } catch (Exception ignored) {}
            }
        }
        throw new McpException("Resource not found or no ready MCP server could read: " + uri);
    }

    private void startServerInternal(ServerEntry entry, Duration timeout) {
        entry.status = McpServerStatus.STARTING;
        entry.errorMessage = null;

        try {
            McpTransport transport = createTransport(entry.config());
            McpClient client = new McpClient(entry.config().name(), transport, mapper);

            entry.transport = transport;
            entry.client = client;

            client.initialize(timeout);

            if (entry.config().trustedReadOnly()) {
                ApprovalPolicy.registerTrustedMcpServer(entry.config().name());
            }

            // Sync tools
            List<McpToolDefinition> toolDefs = client.listTools(timeout);
            List<McpToolAdapter> adapters = new ArrayList<>();
            for (McpToolDefinition def : toolDefs) {
                adapters.add(new McpToolAdapter(entry.config().name(), def, client, mapper, Duration.ofSeconds(60), entry.config().trustedReadOnly()));
            }
            entry.tools = adapters;

            // Sync resources
            try {
                entry.resources = client.listResources(timeout);
            } catch (Exception ignored) {
                entry.resources = List.of();
            }

            // Hook notification listeners
            client.onToolsListChanged(() -> {
                try {
                    List<McpToolDefinition> updatedDefs = client.listTools(Duration.ofSeconds(10));
                    List<McpToolAdapter> updatedAdapters = new ArrayList<>();
                    for (McpToolDefinition def : updatedDefs) {
                        updatedAdapters.add(new McpToolAdapter(entry.config().name(), def, client, mapper, Duration.ofSeconds(60), entry.config().trustedReadOnly()));
                    }
                    entry.tools = updatedAdapters;
                    refreshRegistry();
                } catch (Exception ignored) {}
            });

            client.onResourcesListChanged(() -> {
                try {
                    entry.resources = client.listResources(Duration.ofSeconds(10));
                } catch (Exception ignored) {}
            });

            entry.status = McpServerStatus.READY;
            refreshRegistry();
        } catch (Exception e) {
            entry.status = McpServerStatus.ERROR;
            entry.errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            stopServerInternal(entry);
        }
    }

    private void stopServerInternal(ServerEntry entry) {
        if (entry.transport() != null) {
            try {
                entry.transport().close();
            } catch (Exception ignored) {}
            entry.transport = null;
        }
        entry.client = null;
        entry.tools = List.of();
        entry.resources = List.of();
        if (entry.status() != McpServerStatus.DISABLED && entry.status() != McpServerStatus.ERROR) {
            entry.status = McpServerStatus.STOPPED;
        }
    }

    private McpTransport createTransport(McpServerConfig config) {
        if (config.transportType() == McpTransportType.STDIO) {
            return new StdioMcpTransport(config.command(), config.args(), config.env(), workingDirectory, mapper);
        } else if (config.transportType() == McpTransportType.HTTP) {
            return new StreamableHttpMcpTransport(config.url(), config.headers(), null, mapper);
        } else {
            throw new IllegalArgumentException("Unsupported transport type: " + config.transportType());
        }
    }

    private void refreshRegistry() {
        if (toolRegistry != null) {
            List<McpToolAdapter> allTools = getAllTools();
            toolRegistry.updateDynamicTools(allTools);
            List<com.xhlcli.model.ToolDefinition> currentDefs = toolRegistry.definitions();
            for (Consumer<List<com.xhlcli.model.ToolDefinition>> listener : toolsUpdatedListeners) {
                try {
                    listener.accept(currentDefs);
                } catch (Exception ignored) {}
            }
        }
    }

    @Override
    public void close() {
        for (ServerEntry server : servers.values()) {
            stopServerInternal(server);
        }
        executor.shutdownNow();
    }
}
