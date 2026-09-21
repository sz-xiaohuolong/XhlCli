package com.xhlcli.mcp.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.mcp.model.McpServerConfig;
import com.xhlcli.mcp.model.McpTransportType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads and merges user-level and project-level MCP server configurations,
 * performing strict environment variable expansion (${VAR}).
 */
public final class McpConfigLoader {

    private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\$\\{([a-zA-Z_0-9]+)\\}");

    private final ObjectMapper mapper;

    public McpConfigLoader(ObjectMapper mapper) {
        this.mapper = mapper != null ? mapper : new ObjectMapper();
    }

    public record LoadResult(
            Map<String, McpServerConfig> servers,
            Map<String, String> errors
    ) {
        public LoadResult {
            servers = Map.copyOf(servers);
            errors = Map.copyOf(errors);
        }
    }

    /**
     * Loads MCP configs from user and project files, resolving variables against merged environment.
     */
    public LoadResult load(Path userConfigFile, Path projectConfigFile, Map<String, String> environment) {
        Map<String, RawServerEntry> rawMerged = new LinkedHashMap<>();
        Map<String, String> loadErrors = new LinkedHashMap<>();

        // 1. Read user config if present
        if (userConfigFile != null && Files.isRegularFile(userConfigFile)) {
            readConfigFile(userConfigFile, rawMerged, loadErrors);
        }

        // 2. Read project config if present (overrides user config)
        if (projectConfigFile != null && Files.isRegularFile(projectConfigFile)) {
            readConfigFile(projectConfigFile, rawMerged, loadErrors);
        }

        Map<String, String> env = environment != null ? environment : Map.of();
        Map<String, McpServerConfig> resolvedServers = new LinkedHashMap<>();

        // 3. Resolve each server entry
        for (Map.Entry<String, RawServerEntry> entry : rawMerged.entrySet()) {
            String serverName = entry.getKey();
            RawServerEntry raw = entry.getValue();

            try {
                McpServerConfig resolved = resolveServer(serverName, raw, env);
                resolvedServers.put(serverName, resolved);
            } catch (UnresolvedEnvException uee) {
                loadErrors.put(serverName, "Missing required environment variable: " + uee.getVariableName());
            } catch (Exception ex) {
                loadErrors.put(serverName, "Configuration error: " + ex.getMessage());
            }
        }

        return new LoadResult(resolvedServers, loadErrors);
    }

    private void readConfigFile(Path file, Map<String, RawServerEntry> target, Map<String, String> errors) {
        try {
            JsonNode root = mapper.readTree(file.toFile());
            JsonNode serversNode = root.path("mcpServers");
            if (!serversNode.isObject()) {
                return;
            }

            Iterator<Map.Entry<String, JsonNode>> fields = serversNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String serverName = field.getKey();
                JsonNode serverNode = field.getValue();
                target.put(serverName, new RawServerEntry(serverName, serverNode, file.toAbsolutePath().toString()));
            }
        } catch (Exception e) {
            errors.put(file.getFileName().toString(), "Failed to parse JSON file " + file + ": " + e.getMessage());
        }
    }

    private McpServerConfig resolveServer(String name, RawServerEntry raw, Map<String, String> env) {
        JsonNode node = raw.node();
        boolean disabled = node.path("disabled").asBoolean(false);
        boolean trustedReadOnly = node.path("trustedReadOnly").asBoolean(false);

        String url = node.path("url").asText(null);
        String command = node.path("command").asText(null);

        if (url != null && !url.isBlank()) {
            // HTTP Transport
            String resolvedUrl = expandString(url, env);
            Map<String, String> headers = new LinkedHashMap<>();
            JsonNode headersNode = node.path("headers");
            if (headersNode.isObject()) {
                headersNode.fields().forEachRemaining(entry -> {
                    headers.put(entry.getKey(), expandString(entry.getValue().asText(), env));
                });
            }
            return new McpServerConfig(name, McpTransportType.HTTP, null, List.of(), Map.of(),
                    resolvedUrl, headers, disabled, trustedReadOnly, raw.sourcePath());
        } else if (command != null && !command.isBlank()) {
            // STDIO Transport
            String resolvedCommand = expandString(command, env);
            List<String> args = new ArrayList<>();
            JsonNode argsNode = node.path("args");
            if (argsNode.isArray()) {
                for (JsonNode arg : argsNode) {
                    args.add(expandString(arg.asText(), env));
                }
            }

            Map<String, String> serverEnv = new LinkedHashMap<>();
            JsonNode envNode = node.path("env");
            if (envNode.isObject()) {
                envNode.fields().forEachRemaining(entry -> {
                    serverEnv.put(entry.getKey(), expandString(entry.getValue().asText(), env));
                });
            }

            return new McpServerConfig(name, McpTransportType.STDIO, resolvedCommand, args, serverEnv,
                    null, Map.of(), disabled, trustedReadOnly, raw.sourcePath());
        } else {
            throw new IllegalArgumentException("Server configuration must specify either 'command' (stdio) or 'url' (http)");
        }
    }

    private String expandString(String template, Map<String, String> env) {
        if (template == null || !template.contains("${")) {
            return template;
        }

        Matcher matcher = ENV_VAR_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String varName = matcher.group(1);
            String value = env.get(varName);
            if (value == null || value.isBlank()) {
                throw new UnresolvedEnvException(varName);
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private record RawServerEntry(String name, JsonNode node, String sourcePath) {}

    public static class UnresolvedEnvException extends RuntimeException {
        private final String variableName;

        public UnresolvedEnvException(String variableName) {
            super("Missing required environment variable: " + variableName);
            this.variableName = variableName;
        }

        public String getVariableName() {
            return variableName;
        }
    }
}
