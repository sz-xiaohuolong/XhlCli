package com.xhlcli.tool.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.ToolDefinition;
import com.xhlcli.model.ToolMetadata;
import com.xhlcli.model.ToolOutput;
import com.xhlcli.tool.Tool;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Returns a clock-injected UTC instant for deterministic ReAct demonstrations. */
public final class CurrentTimeTool implements Tool {
    private static final ToolDefinition DEFINITION = createDefinition();

    private final Clock clock;

    public CurrentTimeTool(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolOutput execute(JsonNode arguments, CancellationToken cancellationToken) {
        String currentTime = Instant.now(clock).toString();
        ObjectNode data = JsonNodeFactory.instance.objectNode().put("time", currentTime);
        return new ToolOutput("Current UTC time is " + currentTime + ".", data, "");
    }

    private static ToolDefinition createDefinition() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode().put("type", "object");
        schema.put("additionalProperties", false);
        return new ToolDefinition(
                "current_time", "Returns the current UTC time.", schema,
                new ToolMetadata(ToolMetadata.RiskLevel.LOW, true, true, false, ""));
    }
}
