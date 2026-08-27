package com.xhlcli.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

public record ToolOutput(String summary, JsonNode data, String continueHint) {
    public ToolOutput {
        Objects.requireNonNull(summary, "summary");
        data = Objects.requireNonNull(data, "data").deepCopy();
        continueHint = Objects.requireNonNull(continueHint, "continueHint");
    }

    @Override
    public JsonNode data() {
        return data.deepCopy();
    }
}
