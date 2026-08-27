package com.xhlcli.tool.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.ToolDefinition;
import com.xhlcli.model.ToolMetadata;
import com.xhlcli.model.ToolOutput;
import com.xhlcli.tool.Tool;

/** Safely returns supplied text to demonstrate structured tool calls. */
public final class EchoTool implements Tool {
    private static final ToolDefinition DEFINITION = createDefinition();

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolOutput execute(JsonNode arguments, CancellationToken cancellationToken) {
        String text = arguments.path("text").asText();
        ObjectNode data = JsonNodeFactory.instance.objectNode().put("text", text);
        return new ToolOutput("Echoed " + text.length() + " characters.", data, "");
    }

    private static ToolDefinition createDefinition() {
        ObjectNode properties = JsonNodeFactory.instance.objectNode();
        properties.set("text", JsonNodeFactory.instance.objectNode().put("type", "string"));
        ObjectNode schema = JsonNodeFactory.instance.objectNode().put("type", "object");
        schema.set("properties", properties);
        schema.set("required", JsonNodeFactory.instance.arrayNode().add("text"));
        schema.put("additionalProperties", false);
        return new ToolDefinition(
                "echo_text", "Echoes text without changing it.", schema,
                new ToolMetadata(ToolMetadata.RiskLevel.LOW, true, true, false, ""));
    }
}
