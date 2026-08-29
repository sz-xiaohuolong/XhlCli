package com.xhlcli.tool.local;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.tool.Tool;
import com.xhlcli.model.ToolDefinition;
import com.xhlcli.model.ToolMetadata;
import com.xhlcli.model.ToolMetadata.RiskLevel;
import com.xhlcli.model.ToolOutput;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ListDirTool implements Tool {

    private static final ToolDefinition DEFINITION = createDefinition();
    private final WorkspacePathResolver pathResolver;

    public ListDirTool(WorkspacePathResolver pathResolver) {
        this.pathResolver = pathResolver;
    }

    private static ToolDefinition createDefinition() {
        ObjectNode parameters = JsonNodeFactory.instance.objectNode();
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        
        ObjectNode pathNode = properties.putObject("path");
        pathNode.put("type", "string");
        pathNode.put("description", "Path to list");

        ToolMetadata metadata = new ToolMetadata(RiskLevel.LOW, true, false, true, "local");
        return new ToolDefinition("list_dir", "列出目录内容（仅限项目根目录之内）", parameters, metadata);
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolOutput execute(JsonNode arguments, CancellationToken cancellationToken) throws Exception {
        String pathStr = arguments != null && arguments.has("path") ? arguments.get("path").asText() : ".";
        Path safePath = pathResolver.resolveSafe(pathStr);
        
        if (!Files.isDirectory(safePath)) {
            throw new IllegalArgumentException("Not a directory: " + pathStr);
        }

        try (Stream<Path> stream = Files.list(safePath)) {
            String result = stream
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return !name.equals(".git") && !name.equals("target") && !name.equals("node_modules") && !name.equals(".xhlcli");
                    })
                    .map(p -> {
                        if (Files.isDirectory(p)) {
                            return "[D] " + p.getFileName().toString();
                        } else {
                            return "[F] " + p.getFileName().toString();
                        }
                    })
                    .sorted()
                    .collect(Collectors.joining("\n"));
            
            return new ToolOutput(result, JsonNodeFactory.instance.objectNode(), "");
        }
    }
}
