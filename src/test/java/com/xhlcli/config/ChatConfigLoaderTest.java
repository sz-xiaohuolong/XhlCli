package com.xhlcli.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatConfigLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void appliesCliEnvironmentDotEnvUserConfigAndDefaultPrecedence() throws Exception {
        Path projectDir = Files.createDirectory(tempDir.resolve("project"));
        Path userHome = Files.createDirectory(tempDir.resolve("home"));
        Files.writeString(projectDir.resolve(".env"), """
                DEEPSEEK_API_KEY='dot-env-secret'
                DEEPSEEK_MODEL=dot-env-model
                XHLCLI_READ_TIMEOUT_SECONDS=120
                """);
        writeUserConfig(userHome, """
                {
                  "model": "json-model",
                  "baseUrl": "https://json.example/v1",
                  "readTimeoutSeconds": 90,
                  "logLevel": "INFO"
                }
                """);

        ChatConfig config = ChatConfigLoader.load(
                new String[]{"--model", "cli-model", "--read-timeout", "45"},
                Map.of(
                        "DEEPSEEK_MODEL", "env-model",
                        "DEEPSEEK_BASE_URL", "https://env.example/v1"),
                projectDir,
                userHome);

        assertEquals("dot-env-secret", config.apiKey());
        assertEquals("cli-model", config.model());
        assertEquals(URI.create("https://env.example/v1"), config.baseUrl());
        assertEquals(Duration.ofSeconds(45), config.readTimeout());
        assertEquals(LogLevel.INFO, config.logLevel());
        assertEquals(ConfigSource.DOT_ENV, config.source(ConfigKey.API_KEY));
        assertEquals(ConfigSource.CLI, config.source(ConfigKey.MODEL));
        assertEquals(ConfigSource.ENVIRONMENT, config.source(ConfigKey.BASE_URL));
        assertEquals(ConfigSource.CLI, config.source(ConfigKey.READ_TIMEOUT));
        assertEquals(ConfigSource.USER_CONFIG, config.source(ConfigKey.LOG_LEVEL));
    }

    @Test
    void processEnvironmentOverridesDotEnvForSecrets() throws Exception {
        Path projectDir = Files.createDirectory(tempDir.resolve("project"));
        Path userHome = Files.createDirectory(tempDir.resolve("home"));
        Files.writeString(projectDir.resolve(".env"), "DEEPSEEK_API_KEY=dot-env-key\n");

        ChatConfig config = ChatConfigLoader.load(
                new String[0],
                Map.of("DEEPSEEK_API_KEY", "process-key"),
                projectDir,
                userHome);

        assertEquals("process-key", config.apiKey());
        assertEquals(ConfigSource.ENVIRONMENT, config.source(ConfigKey.API_KEY));
    }

    @Test
    void usesDocumentedDefaultsWhenNoOverridesExist() throws Exception {
        Path projectDir = Files.createDirectory(tempDir.resolve("project"));
        Path userHome = Files.createDirectory(tempDir.resolve("home"));

        ChatConfig config = ChatConfigLoader.load(new String[0], Map.of(), projectDir, userHome);

        assertEquals(null, config.apiKey());
        assertEquals("deepseek-v4-flash", config.model());
        assertEquals(URI.create("https://api.deepseek.com"), config.baseUrl());
        assertEquals(Duration.ofSeconds(30), config.connectTimeout());
        assertEquals(Duration.ofSeconds(300), config.readTimeout());
        assertEquals(Duration.ofSeconds(600), config.requestTimeout());
        assertEquals(new AgentSettings(10, Duration.ofSeconds(600)), config.agentSettings());
        assertEquals(LogLevel.WARN, config.logLevel());
        assertEquals(ConfigSource.MISSING, config.source(ConfigKey.API_KEY));
        assertEquals(ConfigSource.DEFAULT, config.source(ConfigKey.MODEL));
    }

    @Test
    void rejectsSecretsInUserJsonConfig() throws Exception {
        Path projectDir = Files.createDirectory(tempDir.resolve("project"));
        Path userHome = Files.createDirectory(tempDir.resolve("home"));
        writeUserConfig(userHome, "{\"apiKey\":\"must-not-live-here\"}");

        ConfigurationException failure = assertThrows(ConfigurationException.class,
                () -> ChatConfigLoader.load(new String[0], Map.of(), projectDir, userHome));

        assertEquals("~/.xhlcli/config.json must not contain API keys or credentials; use DEEPSEEK_API_KEY instead.",
                failure.getMessage());
    }

    @Test
    void rejectsInvalidUrlAndNonPositiveTimeoutBeforeNetworkUse() throws Exception {
        Path projectDir = Files.createDirectory(tempDir.resolve("project"));
        Path userHome = Files.createDirectory(tempDir.resolve("home"));

        assertThrows(ConfigurationException.class,
                () -> ChatConfigLoader.load(new String[]{"--base-url", "not-a-url"}, Map.of(), projectDir, userHome));
        assertThrows(ConfigurationException.class,
                () -> ChatConfigLoader.load(new String[]{"--request-timeout", "0"}, Map.of(), projectDir, userHome));
    }

    @Test
    void rejectsUnknownOrIncompleteCliOptions() throws Exception {
        Path projectDir = Files.createDirectory(tempDir.resolve("project"));
        Path userHome = Files.createDirectory(tempDir.resolve("home"));

        assertThrows(ConfigurationException.class,
                () -> ChatConfigLoader.load(new String[]{"--api-key", "unsafe"}, Map.of(), projectDir, userHome));
        assertThrows(ConfigurationException.class,
                () -> ChatConfigLoader.load(new String[]{"--model"}, Map.of(), projectDir, userHome));
    }

    @Test
    void acceptsExplicitLocalHttpCompatibilityEndpoint() throws Exception {
        Path projectDir = Files.createDirectory(tempDir.resolve("project"));
        Path userHome = Files.createDirectory(tempDir.resolve("home"));

        ChatConfig config = ChatConfigLoader.load(
                new String[]{"--base-url", "http://localhost:18080/v1"},
                Map.of(),
                projectDir,
                userHome);

        assertEquals(URI.create("http://localhost:18080/v1"), config.baseUrl());
        assertEquals(ConfigSource.CLI, config.source(ConfigKey.BASE_URL));
    }

    @Test
    void resolvesAgentSettingsUsingExistingPrecedence() throws Exception {
        Path projectDir = Files.createDirectory(tempDir.resolve("project"));
        Path userHome = Files.createDirectory(tempDir.resolve("home"));
        Files.writeString(projectDir.resolve(".env"), """
                XHLCLI_AGENT_MAX_ITERATIONS=6
                XHLCLI_AGENT_TIMEOUT_SECONDS=240
                """);
        writeUserConfig(userHome, """
                {"agentMaxIterations": 4, "agentTimeoutSeconds": 120}
                """);

        ChatConfig config = ChatConfigLoader.load(
                new String[]{"--max-iterations", "8", "--agent-timeout", "180"},
                Map.of("XHLCLI_AGENT_MAX_ITERATIONS", "7"),
                projectDir,
                userHome);

        assertEquals(new AgentSettings(8, Duration.ofSeconds(180)), config.agentSettings());
        assertEquals(ConfigSource.CLI, config.source(ConfigKey.AGENT_MAX_ITERATIONS));
        assertEquals(ConfigSource.CLI, config.source(ConfigKey.AGENT_TIMEOUT));
    }

    @Test
    void rejectsAgentSettingsOutsideTheirDocumentedRanges() throws Exception {
        Path projectDir = Files.createDirectory(tempDir.resolve("project"));
        Path userHome = Files.createDirectory(tempDir.resolve("home"));

        assertThrows(ConfigurationException.class,
                () -> ChatConfigLoader.load(new String[]{"--max-iterations", "0"}, Map.of(), projectDir, userHome));
        assertThrows(ConfigurationException.class,
                () -> ChatConfigLoader.load(new String[]{"--max-iterations", "101"}, Map.of(), projectDir, userHome));
        assertThrows(ConfigurationException.class,
                () -> ChatConfigLoader.load(new String[]{"--agent-timeout", "3601"}, Map.of(), projectDir, userHome));
        assertThrows(ConfigurationException.class,
                () -> ChatConfigLoader.load(new String[]{"--agent-timeout", "0"}, Map.of(), projectDir, userHome));
    }

    @Test
    void loadsAgentFieldsFromEnvironmentAndUserConfig() throws Exception {
        Path projectDir = Files.createDirectory(tempDir.resolve("project"));
        Path userHome = Files.createDirectory(tempDir.resolve("home"));
        writeUserConfig(userHome, "{\"agentMaxIterations\": 4}");

        ChatConfig config = ChatConfigLoader.load(
                new String[0], Map.of("XHLCLI_AGENT_TIMEOUT_SECONDS", "300"), projectDir, userHome);

        assertEquals(new AgentSettings(4, Duration.ofSeconds(300)), config.agentSettings());
        assertEquals(ConfigSource.USER_CONFIG, config.source(ConfigKey.AGENT_MAX_ITERATIONS));
        assertEquals(ConfigSource.ENVIRONMENT, config.source(ConfigKey.AGENT_TIMEOUT));
    }

    @Test
    void agentSettingsRejectSubSecondAndOverHourTimeoutsAtConstruction() {
        IllegalArgumentException subSecond = assertThrows(IllegalArgumentException.class,
                () -> new AgentSettings(10, Duration.ofMillis(999)));
        IllegalArgumentException overHour = assertThrows(IllegalArgumentException.class,
                () -> new AgentSettings(10, Duration.ofSeconds(3601)));

        assertEquals("timeout must be between 1 and 3600 seconds", subSecond.getMessage());
        assertEquals("timeout must be between 1 and 3600 seconds", overHour.getMessage());
    }

    private void writeUserConfig(Path userHome, String json) throws Exception {
        Path configDir = Files.createDirectories(userHome.resolve(".xhlcli"));
        Files.writeString(configDir.resolve("config.json"), json);
    }
}
