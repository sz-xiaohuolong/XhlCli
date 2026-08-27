package com.xhlcli.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

public record ToolDefinition(String name, String description, JsonNode parameters, ToolMetadata metadata) {
    public ToolDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        parameters = Objects.requireNonNull(parameters, "parameters").deepCopy();
        Objects.requireNonNull(metadata, "metadata");
    }

    @Override
    public JsonNode parameters() {
        return parameters.deepCopy();
    }
}
