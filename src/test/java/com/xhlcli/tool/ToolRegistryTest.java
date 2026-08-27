package com.xhlcli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.ToolDefinition;
import com.xhlcli.model.ToolMetadata;
import com.xhlcli.model.ToolOutput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolRegistryTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void preservesInsertionOrderLooksUpToolsAndExposesImmutableDefinitions() {
        Tool firstTool = tool("first_tool");
        Tool secondTool = tool("second_tool");

        ToolRegistry registry = new ToolRegistry(List.of(firstTool, secondTool));

        assertEquals(List.of("first_tool", "second_tool"),
                registry.definitions().stream().map(ToolDefinition::name).toList());
        assertEquals(secondTool, registry.find("second_tool").orElseThrow());
        assertFalse(registry.find("missing_tool").isPresent());
        assertThrows(UnsupportedOperationException.class,
                () -> registry.definitions().add(firstTool.definition()));
    }

    @Test
    void rejectsDuplicateNamesAndNamesThatAreNotSnakeCase() {
        Tool firstTool = tool("first_tool");

        assertThrows(IllegalArgumentException.class, () -> new ToolRegistry(List.of(firstTool, firstTool)));
        assertThrows(IllegalArgumentException.class, () -> new ToolRegistry(List.of(tool("not-kebab"))));
    }

    private Tool tool(String name) {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(
                        name,
                        "A test tool.",
                        mapper.createObjectNode().put("type", "object"),
                        ToolMetadata.conservative());
            }

            @Override
            public ToolOutput execute(JsonNode arguments, CancellationToken cancellationToken) {
                return new ToolOutput("ok", mapper.createObjectNode(), "");
            }
        };
    }
}
