package com.xhlcli.context;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Headroom 的显式开关与本机连接配置。 */
public record HeadroomSettings(boolean enabled, URI baseUrl, Duration timeout) {
    public HeadroomSettings {
        String host = baseUrl == null || baseUrl.getHost() == null ? "" : baseUrl.getHost().toLowerCase(Locale.ROOT);
        if (baseUrl == null || !Set.of("http", "https").contains(baseUrl.getScheme())
                || !Set.of("127.0.0.1", "localhost", "::1", "[::1]").contains(host)
                || baseUrl.getRawUserInfo() != null || baseUrl.getRawQuery() != null || baseUrl.getRawFragment() != null
                || !(baseUrl.getRawPath().isEmpty() || baseUrl.getRawPath().equals("/"))
                || baseUrl.getPort() == 0 || baseUrl.getPort() > 65535 || baseUrl.getPort() < -1) {
            throw new IllegalArgumentException("Headroom 地址无效：仅支持无附加路径和凭据的本机回环 HTTP/HTTPS 地址");
        }
        if (timeout == null || timeout.compareTo(Duration.ofMillis(100)) < 0 || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("Headroom 超时无效：XHLCLI_HEADROOM_TIMEOUT_MS 必须在 100～30000 之间");
        }
    }

    public URI endpoint() { return baseUrl.resolve("/v1/compress"); }

    public static HeadroomSettings fromEnvironment(Map<String, String> environment) {
        String flag = environment.getOrDefault("XHLCLI_HEADROOM_ENABLED", "false").strip();
        if (!flag.equalsIgnoreCase("true") && !flag.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException("Headroom 开关无效：XHLCLI_HEADROOM_ENABLED 仅支持 true 或 false");
        }
        boolean enabled = Boolean.parseBoolean(flag);
        if (!enabled) return new HeadroomSettings(false, URI.create("http://127.0.0.1:8787"), Duration.ofMillis(3000));
        URI url;
        try { url = URI.create(environment.getOrDefault("XHLCLI_HEADROOM_URL", "http://127.0.0.1:8787").strip()); }
        catch (IllegalArgumentException failure) { throw new IllegalArgumentException("Headroom 地址无效：请检查 XHLCLI_HEADROOM_URL"); }
        Duration timeout;
        try { timeout = Duration.ofMillis(Long.parseLong(environment.getOrDefault("XHLCLI_HEADROOM_TIMEOUT_MS", "3000").strip())); }
        catch (IllegalArgumentException failure) { throw new IllegalArgumentException("Headroom 超时无效：请检查 XHLCLI_HEADROOM_TIMEOUT_MS"); }
        return new HeadroomSettings(true, url, timeout);
    }
}
