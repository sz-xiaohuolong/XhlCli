package com.xhlcli.mcp.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.mcp.config.McpConfigLoader;
import com.xhlcli.mcp.model.McpServerConfig;
import com.xhlcli.mcp.model.McpServerStatus;
import com.xhlcli.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpServerManagerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ToolRegistry toolRegistry;
    private McpServerManager manager;

    @BeforeEach
    void setUp() {
        toolRegistry = new ToolRegistry(List.of());
        manager = new McpServerManager(toolRegistry, Path.of("."), mapper);
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    @Test
    void testRegisterServersAndStatusHandling() {
        McpServerConfig server1 = McpServerConfig.stdio("srv1", "echo", List.of("1"), Map.of(), true, "test");
        McpServerConfig server2 = McpServerConfig.stdio("srv2", "echo", List.of("2"), Map.of(), false, "test");

        McpConfigLoader.LoadResult loadResult = new McpConfigLoader.LoadResult(
                Map.of("srv1", server1, "srv2", server2),
                Map.of("srv3", "Missing env var")
        );

        manager.registerServers(loadResult);

        Map<String, McpServerManager.ServerEntry> list = manager.listServers();
        assertEquals(3, list.size());

        assertEquals(McpServerStatus.STOPPED, list.get("srv1").status());
        assertEquals(McpServerStatus.STOPPED, list.get("srv2").status());
        assertEquals(McpServerStatus.ERROR, list.get("srv3").status());
        assertTrue(list.get("srv3").errorMessage().contains("Missing env var"));
    }

    @Test
    void testFaultIsolationOnInvalidCommand() {
        McpServerConfig failingServer = McpServerConfig.stdio(
                "failing", "non-existent-binary-xyz-12345", List.of(), Map.of(), false, "test");

        manager.registerServers(new McpConfigLoader.LoadResult(Map.of("failing", failingServer), Map.of()));

        // Starting invalid command shouldn't throw to caller, but transition to ERROR
        manager.startServer("failing", Duration.ofSeconds(2));

        McpServerManager.ServerEntry entry = manager.getServer("failing");
        assertNotNull(entry);
        assertEquals(McpServerStatus.ERROR, entry.status());
        assertNotNull(entry.errorMessage());
    }
}
