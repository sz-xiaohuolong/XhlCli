package com.xhlcli.tool.local;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.tool.Tool;
import com.xhlcli.model.ToolDefinition;
import com.xhlcli.model.ToolMetadata;
import com.xhlcli.model.ToolMetadata.RiskLevel;
import com.xhlcli.model.ToolOutput;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WriteFileTool implements Tool {

    private static final ToolDefinition DEFINITION = createDefinition();
    private final WorkspacePathResolver pathResolver;

    public WriteFileTool(WorkspacePathResolver pathResolver) {
        this.pathResolver = pathResolver;
    }

    private static ToolDefinition createDefinition() {
        ObjectNode parameters = JsonNodeFactory.instance.objectNode();
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        
        ObjectNode pathNode = properties.putObject("path");
        pathNode.put("type", "string");
        
        ObjectNode contentNode = properties.putObject("content");
        contentNode.put("type", "string");
        
        ArrayNode required = parameters.putArray("required");
        required.add("path");
        required.add("content");

        ToolMetadata metadata = new ToolMetadata(RiskLevel.HIGH, false, false, false, "local");
        return new ToolDefinition("write_file", "写入文件内容（仅限项目根目录之内，单文件 5MB 上限）", parameters, metadata);
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolOutput execute(JsonNode arguments, CancellationToken cancellationToken) throws Exception {
        String pathStr = arguments.get("path").asText();
        String content = arguments.get("content").asText();
        
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        if (contentBytes.length > 5 * 1024 * 1024) {
            throw new Exception("File content exceeds 5MB limit");
        }
        
        Path safePath = pathResolver.resolveSafe(pathStr);
        if (safePath.getParent() != null) {
            Files.createDirectories(safePath.getParent());
        }
        
        Files.writeString(safePath, content, StandardCharsets.UTF_8);
        
        return new ToolOutput("文件已写入: " + pathStr, JsonNodeFactory.instance.objectNode(), "");
    }
}
