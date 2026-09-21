package com.xhlcli.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.mcp.protocol.JsonRpcError;
import com.xhlcli.mcp.protocol.JsonRpcNotification;
import com.xhlcli.mcp.protocol.JsonRpcRequest;
import com.xhlcli.mcp.protocol.JsonRpcResponse;
import com.xhlcli.mcp.protocol.McpConstants;
import okhttp3.*;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Streamable HTTP based MCP transport sending JSON-RPC requests via HTTP POST / SSE.
 */
public final class StreamableHttpMcpTransport implements McpTransport {

    private static final int MAX_LOG_LINES = 100;
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    private final String url;
    private final Map<String, String> headers;
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper;

    private final Deque<String> recentLogs = new ArrayDeque<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Consumer<JsonRpcNotification> notificationListener = notification -> {};

    public StreamableHttpMcpTransport(String url, Map<String, String> headers, OkHttpClient httpClient, ObjectMapper mapper) {
        this.url = Objects.requireNonNull(url, "url");
        this.headers = headers != null ? Map.copyOf(headers) : Map.of();
        this.httpClient = httpClient != null ? httpClient : new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(60))
                .writeTimeout(Duration.ofSeconds(30))
                .build();
        this.mapper = mapper != null ? mapper : new ObjectMapper();
    }

    @Override
    public synchronized void start() throws IOException {
        if (running.get()) {
            return;
        }
        running.set(true);
        appendLog("[http] Initialized transport for " + sanitizeUrl(url));
    }

    @Override
    public CompletableFuture<JsonRpcResponse> sendRequest(JsonRpcRequest request, CancellationToken cancellationToken) {
        CompletableFuture<JsonRpcResponse> future = new CompletableFuture<>();
        if (!isAlive()) {
            future.completeExceptionally(new IOException("Transport is not alive"));
            return future;
        }

        try {
            String jsonPayload = mapper.writeValueAsString(request);
            RequestBody body = RequestBody.create(jsonPayload, JSON_MEDIA_TYPE);

            Request.Builder reqBuilder = new Request.Builder()
                    .url(url)
                    .post(body)
                    .header("Accept", "application/json, text/event-stream");

            for (Map.Entry<String, String> entry : headers.entrySet()) {
                reqBuilder.header(entry.getKey(), entry.getValue());
            }

            Request httpRequest = reqBuilder.build();
            Call call = httpClient.newCall(httpRequest);

            if (cancellationToken != null) {
                if (cancellationToken.isCancelled()) {
                    call.cancel();
                    future.completeExceptionally(new CancellationException("Request cancelled: " + request.id()));
                    return future;
                }
                cancellationToken.onCancel(() -> {
                    if (!future.isDone()) {
                        call.cancel();
                        future.completeExceptionally(new CancellationException("Request cancelled: " + request.id()));
                    }
                });
            }

            appendLog("[http:req] POST " + sanitizeUrl(url) + " method=" + request.method() + " id=" + request.id());

            call.enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    appendLog("[http:err] " + e.getMessage());
                    future.completeExceptionally(e);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try (response) {
                        if (!response.isSuccessful()) {
                            String errBody = response.body() != null ? response.body().string() : "";
                            appendLog("[http:fail] HTTP " + response.code() + " " + errBody);
                            JsonRpcError error = new JsonRpcError(response.code(), "HTTP " + response.code() + ": " + errBody, null);
                            future.complete(JsonRpcResponse.error(request.id(), error));
                            return;
                        }

                        String responseBody = response.body() != null ? response.body().string() : "{}";
                        JsonNode node = mapper.readTree(responseBody);
                        JsonRpcResponse jsonRpcResponse = mapper.treeToValue(node, JsonRpcResponse.class);
                        appendLog("[http:res] HTTP 200 id=" + jsonRpcResponse.id());
                        future.complete(jsonRpcResponse);
                    } catch (Exception parseErr) {
                        appendLog("[http:parse-err] " + parseErr.getMessage());
                        future.completeExceptionally(parseErr);
                    }
                }
            });
        } catch (Exception e) {
            future.completeExceptionally(e);
        }

        return future;
    }

    @Override
    public void sendNotification(JsonRpcNotification notification) throws IOException {
        if (!isAlive()) {
            throw new IOException("Transport is not alive");
        }
        String jsonPayload = mapper.writeValueAsString(notification);
        RequestBody body = RequestBody.create(jsonPayload, JSON_MEDIA_TYPE);

        Request.Builder reqBuilder = new Request.Builder()
                .url(url)
                .post(body);

        for (Map.Entry<String, String> entry : headers.entrySet()) {
            reqBuilder.header(entry.getKey(), entry.getValue());
        }

        httpClient.newCall(reqBuilder.build()).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                appendLog("[http:notif-fail] " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                response.close();
            }
        });
    }

    @Override
    public void setNotificationListener(Consumer<JsonRpcNotification> listener) {
        this.notificationListener = listener != null ? listener : notification -> {};
    }

    @Override
    public boolean isAlive() {
        return running.get();
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

    private String sanitizeUrl(String rawUrl) {
        // Redact any query params or credentials in URL
        return rawUrl.replaceAll("([?&][^=]*key=)[^&]*", "$1***");
    }

    @Override
    public synchronized void close() throws IOException {
        running.set(false);
    }
}
