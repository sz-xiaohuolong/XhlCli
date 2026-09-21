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

    private final Map<String, Tool> builtInTools;
    private final List<ToolDefinition> builtInDefinitions;
    private final Map<String, Tool> dynamicTools = new LinkedHashMap<>();
    private volatile List<ToolDefinition> combinedDefinitions;

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
        this.builtInTools = Collections.unmodifiableMap(new LinkedHashMap<>(orderedTools));
        this.builtInDefinitions = List.copyOf(orderedDefinitions);
        this.combinedDefinitions = this.builtInDefinitions;
    }

    public synchronized void updateDynamicTools(List<? extends Tool> newDynamicTools) {
        dynamicTools.clear();
        List<ToolDefinition> dynamicDefs = new ArrayList<>();
        if (newDynamicTools != null) {
            for (Tool tool : newDynamicTools) {
                Tool nonNullTool = Objects.requireNonNull(tool, "dynamic tool");
                ToolDefinition definition = nonNullTool.definition();
                validateDefinition(definition);
                if (builtInTools.containsKey(definition.name())) {
                    throw new IllegalArgumentException("Dynamic tool conflicts with built-in tool: " + definition.name());
                }
                dynamicTools.put(definition.name(), nonNullTool);
                dynamicDefs.add(definition);
            }
        }
        List<ToolDefinition> combined = new ArrayList<>(builtInDefinitions);
        combined.addAll(dynamicDefs);
        this.combinedDefinitions = List.copyOf(combined);
    }

    public Optional<Tool> find(String name) {
        Tool tool = builtInTools.get(name);
        if (tool != null) {
            return Optional.of(tool);
        }
        synchronized (this) {
            return Optional.ofNullable(dynamicTools.get(name));
        }
    }

    public List<ToolDefinition> definitions() {
        return combinedDefinitions;
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
