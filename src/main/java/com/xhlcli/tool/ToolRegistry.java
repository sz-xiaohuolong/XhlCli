package com.xhlcli.tool;

import com.xhlcli.model.ToolDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Collections;
import java.util.ArrayList;
import java.util.regex.Pattern;

/** Ordered registration and lookup for the tools available to an agent run. */
public final class ToolRegistry {
    private static final Pattern TOOL_NAME = Pattern.compile("[a-z][a-z0-9_]*");

    private final Map<String, Tool> tools;
    private final List<ToolDefinition> definitions;

    public ToolRegistry(List<? extends Tool> registeredTools) {
        Objects.requireNonNull(registeredTools, "registeredTools");
        Map<String, Tool> orderedTools = new LinkedHashMap<>();
        List<ToolDefinition> orderedDefinitions = new ArrayList<>();
        for (Tool tool : registeredTools) {
            Tool nonNullTool = Objects.requireNonNull(tool, "tool");
            ToolDefinition definition = nonNullTool.definition();
            validateDefinition(definition);
            if (orderedTools.putIfAbsent(definition.name(), nonNullTool) != null) {
                throw new IllegalArgumentException("Duplicate tool name: " + definition.name());
            }
            orderedDefinitions.add(definition);
        }
        this.tools = Collections.unmodifiableMap(new LinkedHashMap<>(orderedTools));
        this.definitions = List.copyOf(orderedDefinitions);
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<ToolDefinition> definitions() {
        return definitions;
    }

    private static void validateDefinition(ToolDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        if (!TOOL_NAME.matcher(definition.name()).matches()) {
            throw new IllegalArgumentException("Tool name must be lower snake_case: " + definition.name());
        }
        if (definition.description().isBlank()) {
            throw new IllegalArgumentException("Tool description must not be blank.");
        }
        if (!definition.parameters().isObject()
                || !"object".equals(definition.parameters().path("type").asText())) {
            throw new IllegalArgumentException("Tool parameters schema must have an object root.");
        }
    }
}
