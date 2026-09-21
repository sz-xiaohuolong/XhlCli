package com.xhlcli.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.mcp.protocol.JsonRpcError;
import com.xhlcli.mcp.protocol.JsonRpcNotification;
import com.xhlcli.mcp.protocol.JsonRpcRequest;
import com.xhlcli.mcp.protocol.JsonRpcResponse;
import com.xhlcli.mcp.protocol.McpConstants;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Stdio-based MCP transport communicating with a child process via stdin/stdout.
 */
public final class StdioMcpTransport implements McpTransport {

    private static final int MAX_LOG_LINES = 100;

    private final String command;
    private final List<String> args;
    private final Map<String, String> env;
    private final Path workingDirectory;
    private final ObjectMapper mapper;

    private Process process;
    private BufferedWriter writer;
    private Thread stdoutReaderThread;
    private Thread stderrReaderThread;

    private final Map<Object, CompletableFuture<JsonRpcResponse>> pendingRequests = new ConcurrentHashMap<>();
    private final Deque<String> recentLogs = new ArrayDeque<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Consumer<JsonRpcNotification> notificationListener = notification -> {};

    public StdioMcpTransport(String command, List<String> args, Map<String, String> env, Path workingDirectory, ObjectMapper mapper) {
        this.command = Objects.requireNonNull(command, "command");
        this.args = args != null ? List.copyOf(args) : List.of();
        this.env = env != null ? Map.copyOf(env) : Map.of();
        this.workingDirectory = workingDirectory;
        this.mapper = mapper != null ? mapper : new ObjectMapper();
    }

    public StdioMcpTransport(Process process, ObjectMapper mapper) {
        this.command = "custom-process";
        this.args = List.of();
        this.env = Map.of();
        this.workingDirectory = null;
        this.process = Objects.requireNonNull(process, "process");
        this.mapper = mapper != null ? mapper : new ObjectMapper();
    }

    @Override
    public synchronized void start() throws IOException {
        if (running.get()) {
            return;
        }

        if (this.process == null) {
            List<String> fullCommand = new ArrayList<>();
            fullCommand.add(command);
            fullCommand.addAll(args);

            ProcessBuilder pb = new ProcessBuilder(fullCommand);
            if (workingDirectory != null) {
                pb.directory(workingDirectory.toFile());
            }
            if (!env.isEmpty()) {
                pb.environment().putAll(env);
            }
            this.process = pb.start();
        }

        this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.running.set(true);

        this.stdoutReaderThread = new Thread(this::readStdout, "mcp-stdio-stdout-" + command);
        this.stdoutReaderThread.setDaemon(true);
        this.stdoutReaderThread.start();

        this.stderrReaderThread = new Thread(this::readStderr, "mcp-stdio-stderr-" + command);
        this.stderrReaderThread.setDaemon(true);
        this.stderrReaderThread.start();
    }

    @Override
    public CompletableFuture<JsonRpcResponse> sendRequest(JsonRpcRequest request, CancellationToken cancellationToken) {
        if (!isAlive()) {
            CompletableFuture<JsonRpcResponse> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IOException("Transport is not alive"));
            return failed;
        }

        CompletableFuture<JsonRpcResponse> future = new CompletableFuture<>();
        Object idKey = normalizeId(request.id());
        pendingRequests.put(idKey, future);

        try {
            String jsonLine = mapper.writeValueAsString(request);
            synchronized (this) {
                if (writer == null) {
                    throw new IOException("Writer closed");
                }
                writer.write(jsonLine);
                writer.newLine();
                writer.flush();
            }
        } catch (Exception e) {
            pendingRequests.remove(idKey);
            future.completeExceptionally(e);
            return future;
        }

        if (cancellationToken != null) {
            if (cancellationToken.isCancelled()) {
                pendingRequests.remove(idKey);
                future.completeExceptionally(new CancellationException("Request cancelled: " + request.id()));
                return future;
            }
            cancellationToken.onCancel(() -> {
                if (!future.isDone()) {
                    pendingRequests.remove(idKey);
                    // Send cancellation notification to MCP server
                    try {
                        JsonNode params = mapper.createObjectNode().putPOJO("requestId", request.id());
                        sendNotification(JsonRpcNotification.of(McpConstants.NOTIFICATION_CANCELLED, params));
                    } catch (Exception ignored) {}
                    future.completeExceptionally(new CancellationException("Request cancelled: " + request.id()));
                }
            });
        }

        return future;
    }

    @Override
    public synchronized void sendNotification(JsonRpcNotification notification) throws IOException {
        if (!isAlive() || writer == null) {
            throw new IOException("Transport is not alive");
        }
        String jsonLine = mapper.writeValueAsString(notification);
        writer.write(jsonLine);
        writer.newLine();
        writer.flush();
    }

    @Override
    public void setNotificationListener(Consumer<JsonRpcNotification> listener) {
        this.notificationListener = listener != null ? listener : notification -> {};
    }

    @Override
    public boolean isAlive() {
        return running.get() && process != null && process.isAlive();
    }

    @Override
    public synchronized List<String> getRecentLogs() {
        return new ArrayList<>(recentLogs);
    }

    private synchronized void appendLog(String line) {
        if (recentLogs.size() >= MAX_LOG_LINES) {
            recentLogs.removeFirst();
        }
        recentLogs.addLast(line);
    }

    private void readStdout() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                try {
                    JsonNode node = mapper.readTree(line);
                    if (!node.isObject() || !"2.0".equals(node.path("jsonrpc").asText())) {
                        // Non-JSON-RPC stdout output - buffer as diagnostic log to prevent crash
                        appendLog("[stdout:non-protocol] " + line);
                        continue;
                    }

                    if (node.has("method") && !node.has("id")) {
                        // Inbound Notification
                        JsonRpcNotification notification = mapper.treeToValue(node, JsonRpcNotification.class);
                        notificationListener.accept(notification);
                    } else if (node.has("id")) {
                        // Response
                        Object id = node.path("id").isNumber() ? node.path("id").numberValue() : node.path("id").asText();
                        CompletableFuture<JsonRpcResponse> future = pendingRequests.remove(normalizeId(id));
                        if (future != null) {
                            JsonRpcResponse response = mapper.treeToValue(node, JsonRpcResponse.class);
                            future.complete(response);
                        } else {
                            appendLog("[stdout:unmatched-response-id] " + id);
                        }
                    } else {
                        appendLog("[stdout:unknown-json] " + line);
                    }
                } catch (Exception e) {
                    appendLog("[stdout:parse-warning] " + line);
                }
            }
        } catch (IOException ignored) {
        } finally {
            failAllPending(new IOException("Process stdout closed"));
        }
    }

    private void readStderr() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                appendLog("[stderr] " + line);
            }
        } catch (IOException ignored) {
        }
    }

    private void failAllPending(Throwable cause) {
        Iterator<Map.Entry<Object, CompletableFuture<JsonRpcResponse>>> it = pendingRequests.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Object, CompletableFuture<JsonRpcResponse>> entry = it.next();
            it.remove();
            entry.getValue().completeExceptionally(cause);
        }
    }

    private Object normalizeId(Object id) {
        if (id instanceof Number n) {
            return n.longValue();
        }
        return String.valueOf(id);
    }

    @Override
    public synchronized void close() throws IOException {
        running.set(false);
        failAllPending(new IOException("Transport closed"));

        if (writer != null) {
            try {
                writer.close();
            } catch (Exception ignored) {}
            writer = null;
        }

        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }
}
