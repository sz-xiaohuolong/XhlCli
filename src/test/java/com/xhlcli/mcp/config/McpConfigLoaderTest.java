package com.xhlcli.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.mcp.model.McpServerConfig;
import com.xhlcli.mcp.model.McpTransportType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpConfigLoaderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final McpConfigLoader loader = new McpConfigLoader(mapper);

    @Test
    void testMergeUserAndProjectConfigWithOverride(@TempDir Path tempDir) throws Exception {
        Path userConfig = tempDir.resolve("user_mcp.json");
        Files.writeString(userConfig, """
                {
                  "mcpServers": {
                    "fetch": {
                      "command": "uvx",
                      "args": ["mcp-fetch"],
                      "trustedReadOnly": true
                    },
                    "weather": {
                      "url": "https://user.weather.com",
                      "headers": { "Authorization": "Bearer ${WEATHER_TOKEN}" }
                    }
                  }
                }
                """);

        Path projectConfig = tempDir.resolve("project_mcp.json");
        Files.writeString(projectConfig, """
                {
                  "mcpServers": {
                    "weather": {
                      "url": "https://project.weather.com/v2",
                      "headers": { "Authorization": "Bearer ${WEATHER_TOKEN}" }
                    }
                  }
                }
                """);

        Map<String, String> env = Map.of("WEATHER_TOKEN", "secret-token-123");

        McpConfigLoader.LoadResult result = loader.load(userConfig, projectConfig, env);

        assertTrue(result.errors().isEmpty());
        assertEquals(2, result.servers().size());

        // Fetch is from user config
        McpServerConfig fetch = result.servers().get("fetch");
        assertNotNull(fetch);
        assertEquals(McpTransportType.STDIO, fetch.transportType());
        assertTrue(fetch.trustedReadOnly());
        assertTrue(fetch.sourcePath().contains("user_mcp.json"));

        // Weather is overridden by project config
        McpServerConfig weather = result.servers().get("weather");
        assertNotNull(weather);
        assertEquals(McpTransportType.HTTP, weather.transportType());
        assertEquals("https://project.weather.com/v2", weather.url());
        assertEquals("Bearer secret-token-123", weather.headers().get("Authorization"));
        assertTrue(weather.sourcePath().contains("project_mcp.json"));
    }

    @Test
    void testMissingEnvVariableReportsErrorWithoutCrashing(@TempDir Path tempDir) throws Exception {
        Path projectConfig = tempDir.resolve("project_mcp.json");
        Files.writeString(projectConfig, """
                {
                  "mcpServers": {
                    "good": {
                      "command": "echo",
                      "args": ["hello"]
                    },
                    "bad": {
                      "url": "https://api.example.com",
                      "headers": { "Authorization": "Bearer ${MISSING_VAR}" }
                    }
                  }
                }
                """);

        McpConfigLoader.LoadResult result = loader.load(null, projectConfig, Map.of());

        assertEquals(1, result.servers().size());
        assertNotNull(result.servers().get("good"));

        assertEquals(1, result.errors().size());
        assertTrue(result.errors().containsKey("bad"));
        assertTrue(result.errors().get("bad").contains("MISSING_VAR"));
    }
}
