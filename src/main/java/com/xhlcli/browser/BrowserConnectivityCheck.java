package com.xhlcli.browser;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.time.Duration;

/**
 * 宿主 Chrome 远程调试端口探活检测。
 */
public class BrowserConnectivityCheck {

    private final OkHttpClient client;

    public BrowserConnectivityCheck() {
        this(new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(2))
                .readTimeout(Duration.ofSeconds(2))
                .callTimeout(Duration.ofSeconds(2))
                .build());
    }

    BrowserConnectivityCheck(OkHttpClient client) {
        this.client = client;
    }

    public ProbeResult probe(int port) {
        if (port < 1024 || port > 65535) {
            return ProbeResult.failed("端口必须在 1024-65535 之间");
        }
        String url = "http://127.0.0.1:" + port + "/json/version";
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return ProbeResult.failed("HTTP " + response.code());
            }
            return ProbeResult.ok("http://127.0.0.1:" + port);
        } catch (Exception e) {
            return ProbeResult.failed(e.getMessage());
        }
    }

    public java.util.List<BrowserTabInfo> fetchTabs(int port) {
        if (port < 1024 || port > 65535) {
            return java.util.List.of();
        }
        String url = "http://127.0.0.1:" + port + "/json/list";
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return java.util.List.of();
            }
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(response.body().string());
            if (!root.isArray()) {
                return java.util.List.of();
            }
            java.util.List<BrowserTabInfo> list = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode node : root) {
                String id = node.path("id").asText("");
                String title = node.path("title").asText("");
                String pageUrl = node.path("url").asText("");
                String type = node.path("type").asText("page");
                list.add(new BrowserTabInfo(id, title, pageUrl, type));
            }
            return list;
        } catch (Exception ignored) {
            return java.util.List.of();
        }
    }

    public record ProbeResult(boolean ok, String browserUrl, String message) {
        public static ProbeResult ok(String browserUrl) {
            return new ProbeResult(true, browserUrl, "ok");
        }

        public static ProbeResult failed(String message) {
            return new ProbeResult(false, null, message == null ? "连接失败" : message);
        }
    }
}
