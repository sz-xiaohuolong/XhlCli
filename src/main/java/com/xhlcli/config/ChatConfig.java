package com.xhlcli.config;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

public record ChatConfig(
        String apiKey,
        String model,
        URI baseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        Duration requestTimeout,
        LogLevel logLevel,
        AgentSettings agentSettings,
        Map<ConfigKey, ConfigSource> sources) {

    public ChatConfig {
        model = Objects.requireNonNull(model, "model");
        baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
        readTimeout = Objects.requireNonNull(readTimeout, "readTimeout");
        requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        logLevel = Objects.requireNonNull(logLevel, "logLevel");
        agentSettings = Objects.requireNonNull(agentSettings, "agentSettings");
        sources = Map.copyOf(sources);
    }

    public ChatConfig(
            String apiKey,
            String model,
            URI baseUrl,
            Duration connectTimeout,
            Duration readTimeout,
            Duration requestTimeout,
            LogLevel logLevel,
            Map<ConfigKey, ConfigSource> sources) {
        this(apiKey, model, baseUrl, connectTimeout, readTimeout, requestTimeout, logLevel, AgentSettings.defaults(), sources);
    }

    public ConfigSource source(ConfigKey key) {
        return sources.getOrDefault(key, ConfigSource.MISSING);
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
