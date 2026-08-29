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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ReadFileTool implements Tool {

    private static final ToolDefinition DEFINITION = createDefinition();
    private final WorkspacePathResolver pathResolver;

    public ReadFileTool(WorkspacePathResolver pathResolver) {
        this.pathResolver = pathResolver;
    }

    private static ToolDefinition createDefinition() {
        ObjectNode parameters = JsonNodeFactory.instance.objectNode();
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        
        ObjectNode pathNode = properties.putObject("path");
        pathNode.put("type", "string");
        
        ObjectNode offsetNode = properties.putObject("offset");
        offsetNode.put("type", "integer");
        
        ObjectNode limitNode = properties.putObject("limit");
        limitNode.put("type", "integer");
        
        ArrayNode required = parameters.putArray("required");
        required.add("path");

        ToolMetadata metadata = new ToolMetadata(RiskLevel.LOW, true, false, true, "local");
        return new ToolDefinition("read_file", "读取文件内容（仅限项目根目录之内）；可用 offset/limit 按行读取，避免把大文件整段塞进上下文", parameters, metadata);
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolOutput execute(JsonNode arguments, CancellationToken cancellationToken) throws Exception {
        String pathStr = arguments.get("path").asText();
        Path safePath = pathResolver.resolveSafe(pathStr);
        
        if (!Files.isRegularFile(safePath)) {
            throw new IllegalArgumentException("Not a regular file: " + pathStr);
        }

        List<String> lines = Files.readAllLines(safePath);
        
        if (!arguments.has("offset") && !arguments.has("limit")) {
            String content = String.join("\n", lines);
            return new ToolOutput(content, JsonNodeFactory.instance.objectNode(), "");
        }
        
        int offset = arguments.has("offset") ? arguments.get("offset").asInt() : 1;
        int limit = arguments.has("limit") ? arguments.get("limit").asInt() : 200;
        
        if (limit > 2000) {
            limit = 2000;
        }
        if (offset < 1) {
            offset = 1;
        }
        
        StringBuilder sb = new StringBuilder();
        int startIndex = offset - 1;
        int endIndex = Math.min(startIndex + limit, lines.size());
        
        for (int i = startIndex; i < endIndex; i++) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(String.format("%5d | %s", i + 1, lines.get(i)));
        }
        
        if (endIndex < lines.size()) {
            sb.append("\n...(已截断，可用 offset=").append(endIndex + 1).append(" 继续读取)");
        }
        
        return new ToolOutput(sb.toString(), JsonNodeFactory.instance.objectNode(), "");
    }
}
