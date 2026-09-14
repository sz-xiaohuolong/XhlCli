package com.xhlcli.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ChatConfigLoader {
    private static final String DEFAULT_MODEL = "deepseek-v4-flash";
    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    private static final long DEFAULT_CONNECT_TIMEOUT_SECONDS = 30;
    private static final long DEFAULT_READ_TIMEOUT_SECONDS = 300;
    private static final long DEFAULT_REQUEST_TIMEOUT_SECONDS = 600;
    private static final Set<String> SECRET_FIELD_NAMES = Set.of(
            "apikey", "api_key", "api-key", "token", "authorization", "password");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private ChatConfigLoader() {}

    public static ChatConfig load(
            String[] args,
            Map<String, String> environment,
            Path projectDirectory,
            Path userHome) throws ConfigurationException {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(projectDirectory, "projectDirectory");
        Objects.requireNonNull(userHome, "userHome");

        Map<ConfigKey, String> cli = parseCli(args);
        Map<String, String> dotEnv = readDotEnv(projectDirectory.resolve(".env"));
        UserConfig userConfig = readUserConfig(userHome.resolve(".xhlcli").resolve("config.json"));
        EnumMap<ConfigKey, ConfigSource> sources = new EnumMap<>(ConfigKey.class);

        Resolved<String> apiKey = firstNonBlankOrMissing(
                resolved(environment.get("DEEPSEEK_API_KEY"), ConfigSource.ENVIRONMENT),
                resolved(dotEnv.get("DEEPSEEK_API_KEY"), ConfigSource.DOT_ENV));
        sources.put(ConfigKey.API_KEY, apiKey.source());

        Resolved<String> model = firstNonBlank(
                resolved(cli.get(ConfigKey.MODEL), ConfigSource.CLI),
                resolved(environment.get("DEEPSEEK_MODEL"), ConfigSource.ENVIRONMENT),
                resolved(dotEnv.get("DEEPSEEK_MODEL"), ConfigSource.DOT_ENV),
                resolved(userConfig.model(), ConfigSource.USER_CONFIG),
                new Resolved<>(DEFAULT_MODEL, ConfigSource.DEFAULT));
        sources.put(ConfigKey.MODEL, model.source());

        Resolved<String> baseUrl = firstNonBlank(
                resolved(cli.get(ConfigKey.BASE_URL), ConfigSource.CLI),
                resolved(environment.get("DEEPSEEK_BASE_URL"), ConfigSource.ENVIRONMENT),
                resolved(dotEnv.get("DEEPSEEK_BASE_URL"), ConfigSource.DOT_ENV),
                resolved(userConfig.baseUrl(), ConfigSource.USER_CONFIG),
                new Resolved<>(DEFAULT_BASE_URL, ConfigSource.DEFAULT));
        sources.put(ConfigKey.BASE_URL, baseUrl.source());

        Resolved<String> connectTimeout = resolveSetting(
                ConfigKey.CONNECT_TIMEOUT,
                "XHLCLI_CONNECT_TIMEOUT_SECONDS",
                cli,
                environment,
                dotEnv,
                userConfig.connectTimeoutSeconds(),
                DEFAULT_CONNECT_TIMEOUT_SECONDS);
        Resolved<String> readTimeout = resolveSetting(
                ConfigKey.READ_TIMEOUT,
                "XHLCLI_READ_TIMEOUT_SECONDS",
                cli,
                environment,
                dotEnv,
                userConfig.readTimeoutSeconds(),
                DEFAULT_READ_TIMEOUT_SECONDS);
        Resolved<String> requestTimeout = resolveSetting(
                ConfigKey.REQUEST_TIMEOUT,
                "XHLCLI_REQUEST_TIMEOUT_SECONDS",
                cli,
                environment,
                dotEnv,
                userConfig.requestTimeoutSeconds(),
                DEFAULT_REQUEST_TIMEOUT_SECONDS);
        sources.put(ConfigKey.CONNECT_TIMEOUT, connectTimeout.source());
        sources.put(ConfigKey.READ_TIMEOUT, readTimeout.source());
        sources.put(ConfigKey.REQUEST_TIMEOUT, requestTimeout.source());

        Resolved<String> agentMaxIterations = resolveSetting(
                ConfigKey.AGENT_MAX_ITERATIONS,
                "XHLCLI_AGENT_MAX_ITERATIONS",
                cli,
                environment,
                dotEnv,
                userConfig.agentMaxIterations(),
                AgentSettings.DEFAULT_MAX_ITERATIONS);
        Resolved<String> agentTimeout = resolveSetting(
                ConfigKey.AGENT_TIMEOUT,
                "XHLCLI_AGENT_TIMEOUT_SECONDS",
                cli,
                environment,
                dotEnv,
                userConfig.agentTimeoutSeconds(),
                AgentSettings.DEFAULT_TIMEOUT.toSeconds());
        Resolved<String> maxConcurrency = resolveSetting(
                ConfigKey.MAX_CONCURRENCY,
                "XHLCLI_MAX_CONCURRENCY",
                cli,
                environment,
                dotEnv,
                userConfig.maxConcurrency(),
                AgentSettings.DEFAULT_MAX_CONCURRENCY);
        Resolved<String> toolTimeout = resolveSetting(
                ConfigKey.TOOL_TIMEOUT,
                "XHLCLI_TOOL_TIMEOUT_SECONDS",
                cli,
                environment,
                dotEnv,
                userConfig.toolTimeoutSeconds(),
                AgentSettings.DEFAULT_TOOL_TIMEOUT.toSeconds());
        sources.put(ConfigKey.AGENT_MAX_ITERATIONS, agentMaxIterations.source());
        sources.put(ConfigKey.AGENT_TIMEOUT, agentTimeout.source());
        sources.put(ConfigKey.MAX_CONCURRENCY, maxConcurrency.source());
        sources.put(ConfigKey.TOOL_TIMEOUT, toolTimeout.source());

        Resolved<String> logLevel = firstNonBlank(
                resolved(cli.get(ConfigKey.LOG_LEVEL), ConfigSource.CLI),
                resolved(environment.get("XHLCLI_LOG_LEVEL"), ConfigSource.ENVIRONMENT),
                resolved(dotEnv.get("XHLCLI_LOG_LEVEL"), ConfigSource.DOT_ENV),
                resolved(userConfig.logLevel(), ConfigSource.USER_CONFIG),
                new Resolved<>(LogLevel.WARN.name(), ConfigSource.DEFAULT));
        sources.put(ConfigKey.LOG_LEVEL, logLevel.source());

        return new ChatConfig(
                apiKey.value(),
                model.value().trim(),
                parseBaseUrl(baseUrl.value()),
                parsePositiveDuration(connectTimeout.value(), "Connect timeout"),
                parsePositiveDuration(readTimeout.value(), "Read timeout"),
                parsePositiveDuration(requestTimeout.value(), "Request timeout"),
                LogLevel.parse(logLevel.value()),
                new AgentSettings(
                        (int) parseRange(agentMaxIterations.value(), "Agent max iterations", 1, 100),
                        Duration.ofSeconds(parseRange(agentTimeout.value(), "Agent timeout", 1, 3600)),
                        (int) parseRange(maxConcurrency.value(), "Max concurrency", 1, 16),
                        Duration.ofSeconds(parseRange(toolTimeout.value(), "Tool timeout", 1, 3600))),
                sources);
    }

    private static Map<ConfigKey, String> parseCli(String[] args) throws ConfigurationException {
        Map<ConfigKey, String> values = new EnumMap<>(ConfigKey.class);
        for (int index = 0; index < args.length; index++) {
            ConfigKey key = switch (args[index]) {
                case "--model" -> ConfigKey.MODEL;
                case "--base-url" -> ConfigKey.BASE_URL;
                case "--connect-timeout" -> ConfigKey.CONNECT_TIMEOUT;
                case "--read-timeout" -> ConfigKey.READ_TIMEOUT;
                case "--request-timeout" -> ConfigKey.REQUEST_TIMEOUT;
                case "--max-iterations" -> ConfigKey.AGENT_MAX_ITERATIONS;
                case "--agent-timeout" -> ConfigKey.AGENT_TIMEOUT;
                case "--max-concurrency" -> ConfigKey.MAX_CONCURRENCY;
                case "--tool-timeout" -> ConfigKey.TOOL_TIMEOUT;
                case "--log-level" -> ConfigKey.LOG_LEVEL;
                default -> throw new ConfigurationException("Unknown option: " + args[index]);
            };
            if (++index >= args.length || args[index].isBlank()) {
                throw new ConfigurationException("Missing value for option: " + args[index - 1]);
            }
            values.put(key, args[index].trim());
        }
        return values;
    }

    private static Map<String, String> readDotEnv(Path path) throws ConfigurationException {
        if (!Files.isRegularFile(path)) {
            return Map.of();
        }
        try {
            Map<String, String> values = new HashMap<>();
            for (String rawLine : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int separator = line.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                String key = line.substring(0, separator).trim();
                String value = stripMatchingQuotes(line.substring(separator + 1).trim());
                values.put(key, value);
            }
            return Map.copyOf(values);
        } catch (IOException failure) {
            throw new ConfigurationException("Unable to read the project .env file.", failure);
        }
    }

    private static String stripMatchingQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static UserConfig readUserConfig(Path path) throws ConfigurationException {
        if (!Files.isRegularFile(path)) {
            return UserConfig.empty();
        }
        try {
            JsonNode root = MAPPER.readTree(path.toFile());
            if (containsSecretField(root)) {
                throw new ConfigurationException(
                        "~/.xhlcli/config.json must not contain API keys or credentials; use DEEPSEEK_API_KEY instead.");
            }
            return MAPPER.treeToValue(root, UserConfig.class);
        } catch (ConfigurationException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new ConfigurationException("Unable to read ~/.xhlcli/config.json.", failure);
        }
    }

    private static boolean containsSecretField(JsonNode node) {
        if (node == null) {
            return false;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (SECRET_FIELD_NAMES.contains(field.getKey().toLowerCase(Locale.ROOT))
                        || containsSecretField(field.getValue())) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsSecretField(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Resolved<String> resolveSetting(
            ConfigKey key,
            String environmentName,
            Map<ConfigKey, String> cli,
            Map<String, String> environment,
            Map<String, String> dotEnv,
            Long userValue,
            long defaultValue) {
        return firstNonBlank(
                resolved(cli.get(key), ConfigSource.CLI),
                resolved(environment.get(environmentName), ConfigSource.ENVIRONMENT),
                resolved(dotEnv.get(environmentName), ConfigSource.DOT_ENV),
                resolved(userValue == null ? null : userValue.toString(), ConfigSource.USER_CONFIG),
                new Resolved<>(Long.toString(defaultValue), ConfigSource.DEFAULT));
    }

    @SafeVarargs
    private static <T> Resolved<T> firstNonBlank(Resolved<T>... candidates) {
        for (Resolved<T> candidate : candidates) {
            if (candidate != null && candidate.hasValue()) {
                return candidate;
            }
        }
        throw new IllegalStateException("A default configuration value is required");
    }

    @SafeVarargs
    private static <T> Resolved<T> firstNonBlankOrMissing(Resolved<T>... candidates) {
        for (Resolved<T> candidate : candidates) {
            if (candidate != null && candidate.hasValue()) {
                return candidate;
            }
        }
        return new Resolved<>(null, ConfigSource.MISSING);
    }

    private static Resolved<String> resolved(String value, ConfigSource source) {
        return new Resolved<>(value, source);
    }

    private static URI parseBaseUrl(String value) throws ConfigurationException {
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme();
            if (!uri.isAbsolute()
                    || scheme == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null
                    || uri.getHost().isBlank()) {
                throw new ConfigurationException("Base URL must be an absolute HTTP or HTTPS URL with a host.");
            }
            return uri;
        } catch (URISyntaxException failure) {
            throw new ConfigurationException("Base URL must be an absolute HTTP or HTTPS URL with a host.", failure);
        }
    }

    private static Duration parsePositiveDuration(String value, String label) throws ConfigurationException {
        try {
            long seconds = Long.parseLong(value.trim());
            if (seconds <= 0) {
                throw new NumberFormatException("not positive");
            }
            return Duration.ofSeconds(seconds);
        } catch (NumberFormatException failure) {
            throw new ConfigurationException(label + " must be a positive number of seconds.");
        }
    }

    private static long parseRange(String value, String label, long minimum, long maximum) throws ConfigurationException {
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed < minimum || parsed > maximum) {
                throw new NumberFormatException("outside range");
            }
            return parsed;
        } catch (NumberFormatException failure) {
            throw new ConfigurationException(label + " must be between " + minimum + " and " + maximum + ".");
        }
    }

    private record Resolved<T>(T value, ConfigSource source) {
        boolean hasValue() {
            return value != null && (!(value instanceof String string) || !string.isBlank());
        }
    }

    private record UserConfig(
            String model,
            String baseUrl,
            Long connectTimeoutSeconds,
            Long readTimeoutSeconds,
            Long requestTimeoutSeconds,
            Long agentMaxIterations,
            Long agentTimeoutSeconds,
            String logLevel,
            Long maxConcurrency,
            Long toolTimeoutSeconds) {
        static UserConfig empty() {
            return new UserConfig(null, null, null, null, null, null, null, null, null, null);
        }
    }
}
