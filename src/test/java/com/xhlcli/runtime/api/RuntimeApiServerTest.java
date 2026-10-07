package com.xhlcli.runtime.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeApiServerTest {

    @TempDir
    Path tempDir;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @Test
    void rejectsEmptyApiKey() throws Exception {
        Path db = tempDir.resolve("empty-key.db");
        try (RuntimeThreadStore store = new RuntimeThreadStore(db)) {
            assertThrows(IllegalArgumentException.class,
                    () -> new RuntimeApiServer(store, p -> "ok", 0, ""));
            assertThrows(IllegalArgumentException.class,
                    () -> new RuntimeApiServer(store, p -> "ok", 0, null));
        }
    }

    @Test
    void requiresAuthentication() throws Exception {
        Path db = tempDir.resolve("auth.db");
        try (RuntimeThreadStore store = new RuntimeThreadStore(db);
             RuntimeApiServer server = new RuntimeApiServer(store, p -> "ok", 0, "secret-test-key")) {
            server.start();
            int port = server.port();

            // 1. No header -> 401
            HttpRequest reqNoAuth = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/v1/threads"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> respNoAuth = client.send(reqNoAuth, HttpResponse.BodyHandlers.ofString());
            assertEquals(401, respNoAuth.statusCode());

            // 2. Wrong header -> 401
            HttpRequest reqWrongAuth = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/v1/threads"))
                    .header("Authorization", "Bearer wrong-key")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> respWrongAuth = client.send(reqWrongAuth, HttpResponse.BodyHandlers.ofString());
            assertEquals(401, respWrongAuth.statusCode());

            // 3. Correct Bearer header -> 200
            HttpRequest reqValidBearer = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/v1/threads"))
                    .header("Authorization", "Bearer secret-test-key")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> respValidBearer = client.send(reqValidBearer, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, respValidBearer.statusCode());
            assertTrue(respValidBearer.body().contains("\"object\":\"thread\""));

            // 4. Correct Direct X-XhlCLI-API-Key header -> 200
            HttpRequest reqValidDirect = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/v1/threads"))
                    .header("X-XhlCLI-API-Key", "secret-test-key")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> respValidDirect = client.send(reqValidDirect, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, respValidDirect.statusCode());
        }
    }

    @Test
    void threadTurnAndEventsFullLifecycle() throws Exception {
        Path db = tempDir.resolve("lifecycle.db");
        try (RuntimeThreadStore store = new RuntimeThreadStore(db);
             RuntimeApiServer server = new RuntimeApiServer(store, prompt -> "ECHO: " + prompt, 0, "api-key-123")) {
            server.start();
            int port = server.port();

            // 1. Create thread
            HttpRequest createReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/v1/threads"))
                    .header("Authorization", "Bearer api-key-123")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> createResp = client.send(createReq, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, createResp.statusCode());

            com.fasterxml.jackson.databind.JsonNode threadNode =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(createResp.body());
            String threadId = threadNode.get("id").asText();
            assertTrue(threadId.startsWith("thread_"));

            // 2. Submit Turn
            HttpRequest turnReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/v1/threads/" + threadId + "/turns"))
                    .header("Authorization", "Bearer api-key-123")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"input\":\"hello api server\"}"))
                    .build();
            HttpResponse<String> turnResp = client.send(turnReq, HttpResponse.BodyHandlers.ofString());
            assertEquals(202, turnResp.statusCode());
            assertTrue(turnResp.body().contains("\"status\":\"running\""));

            // 3. Poll SSE Events until completed
            String sseBody = "";
            for (int i = 0; i < 30; i++) {
                HttpRequest eventsReq = HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + "/v1/threads/" + threadId + "/events"))
                        .header("Authorization", "Bearer api-key-123")
                        .GET()
                        .build();
                HttpResponse<String> eventsResp = client.send(eventsReq, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, eventsResp.statusCode());
                assertTrue(eventsResp.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"));
                sseBody = eventsResp.body();
                if (sseBody.contains("event: turn.completed")) {
                    break;
                }
                Thread.sleep(50);
            }

            assertTrue(sseBody.contains("event: thread.created"));
            assertTrue(sseBody.contains("event: turn.started"));
            assertTrue(sseBody.contains("event: message.delta"));
            assertTrue(sseBody.contains("ECHO: hello api server"));
            assertTrue(sseBody.contains("event: turn.completed"));

            // 4. Test after cursor
            // Extract first event ID: "id: 1"
            long firstId = 1;
            HttpRequest afterReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/v1/threads/" + threadId + "/events?after=" + firstId))
                    .header("Authorization", "Bearer api-key-123")
                    .GET()
                    .build();
            HttpResponse<String> afterResp = client.send(afterReq, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, afterResp.statusCode());
            // Should not contain thread.created event
            assertTrue(!afterResp.body().contains("event: thread.created"));
            assertTrue(afterResp.body().contains("event: message.delta"));
        }
    }
}
