package com.xhlcli.tool.local;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.ToolDefinition;
import com.xhlcli.model.ToolMetadata;
import com.xhlcli.model.ToolOutput;
import com.xhlcli.tool.Tool;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ApplyPatchTool implements Tool {

    private static final ToolDefinition DEFINITION = createDefinition();

    public static ToolDefinition createDefinition() {
        ObjectNode properties = JsonNodeFactory.instance.objectNode();
        properties.set("path", JsonNodeFactory.instance.objectNode().put("type", "string"));
        properties.set("old_text", JsonNodeFactory.instance.objectNode().put("type", "string"));
        properties.set("new_text", JsonNodeFactory.instance.objectNode().put("type", "string"));

        ArrayNode required = JsonNodeFactory.instance.arrayNode();
        required.add("path").add("old_text").add("new_text");

        ObjectNode parameters = JsonNodeFactory.instance.objectNode();
        parameters.put("type", "object");
        parameters.set("properties", properties);
        parameters.set("required", required);
        parameters.put("additionalProperties", false);

        return new ToolDefinition(
                "apply_patch",
                "对已有文件进行精确局部替换（仅限项目根目录之内）；old_text 必须在文件中唯一匹配",
                parameters,
                new ToolMetadata(ToolMetadata.RiskLevel.HIGH, false, false, false, "file")
        );
    }

    private final WorkspacePathResolver resolver;

    public ApplyPatchTool(WorkspacePathResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolOutput execute(JsonNode arguments, CancellationToken cancellationToken) throws Exception {
        if (!arguments.has("path") || !arguments.has("old_text") || !arguments.has("new_text")) {
            return new ToolOutput("Missing required parameters", JsonNodeFactory.instance.objectNode(), "");
        }

        String pathStr = arguments.get("path").asText();
        String oldText = arguments.get("old_text").asText();
        String newText = arguments.get("new_text").asText();

        Path path;
        try {
            path = resolver.resolveSafe(pathStr);
        } catch (Exception e) {
            return new ToolOutput(e.getMessage(), JsonNodeFactory.instance.objectNode(), "");
        }

        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            return new ToolOutput("文件不存在或不是普通文件: " + pathStr, JsonNodeFactory.instance.objectNode(), "");
        }

        String content = Files.readString(path);
        
        int occurrences = 0;
        int index = 0;
        while ((index = content.indexOf(oldText, index)) != -1) {
            occurrences++;
            index += oldText.length();
        }

        if (occurrences == 0) {
            return new ToolOutput("未找到匹配的待替换文本", JsonNodeFactory.instance.objectNode(), "");
        } else if (occurrences > 1) {
            return new ToolOutput("匹配到 " + occurrences + " 处待替换文本，请提供更多上下文以唯一定位", JsonNodeFactory.instance.objectNode(), "");
        }

        String newContent = content.replace(oldText, newText);
        Files.writeString(path, newContent);

        return new ToolOutput("替换成功", JsonNodeFactory.instance.objectNode(), "");
    }
}
