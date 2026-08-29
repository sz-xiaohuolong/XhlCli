package com.xhlcli.tool.local;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.ToolDefinition;
import com.xhlcli.model.ToolMetadata;
import com.xhlcli.model.ToolOutput;
import com.xhlcli.tool.Tool;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class GitDiffTool implements Tool {

    private static final ToolDefinition DEFINITION = createDefinition();

    public static ToolDefinition createDefinition() {
        ObjectNode properties = JsonNodeFactory.instance.objectNode();
        properties.set("path", JsonNodeFactory.instance.objectNode().put("type", "string"));

        ObjectNode parameters = JsonNodeFactory.instance.objectNode();
        parameters.put("type", "object");
        parameters.set("properties", properties);
        parameters.put("additionalProperties", false);

        return new ToolDefinition(
                "git_diff",
                "查看工作区 Git 差异（仅限项目根目录之内）",
                parameters,
                new ToolMetadata(ToolMetadata.RiskLevel.LOW, true, false, true, "git")
        );
    }

    private final WorkspacePathResolver resolver;

    public GitDiffTool(WorkspacePathResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolOutput execute(JsonNode arguments, CancellationToken cancellationToken) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("diff");

        if (arguments != null && arguments.has("path") && !arguments.get("path").isNull()) {
            String pathStr = arguments.get("path").asText();
            if (!pathStr.isBlank()) {
                command.add("--");
                command.add(pathStr);
            }
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(resolver.projectRoot().toFile());
        pb.redirectErrorStream(true);

        Process process;
        try {
            process = pb.start();
        } catch (Exception e) {
            return new ToolOutput("执行 git 命令失败: " + e.getMessage(), JsonNodeFactory.instance.objectNode(), "");
        }

        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return new ToolOutput("git diff 命令超时", JsonNodeFactory.instance.objectNode(), "");
        }

        int exitCode = process.exitValue();
        String output;
        try (InputStream is = process.getInputStream()) {
            output = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        }

        if (exitCode != 0) {
            return new ToolOutput("git diff 失败 (exit code " + exitCode + "):\n" + output, JsonNodeFactory.instance.objectNode(), "");
        }

        if (output.isEmpty()) {
            return new ToolOutput("工作区没有未暂存的变更", JsonNodeFactory.instance.objectNode(), "");
        }

        return new ToolOutput(output, JsonNodeFactory.instance.objectNode(), "");
    }
}
