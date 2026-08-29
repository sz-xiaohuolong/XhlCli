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

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

public final class ExecuteCommandTool implements Tool {

    private static final ToolDefinition DEFINITION = createDefinition();

    public static ToolDefinition createDefinition() {
        ObjectNode properties = JsonNodeFactory.instance.objectNode();
        properties.set("command", JsonNodeFactory.instance.objectNode().put("type", "string"));
        properties.set("timeout_seconds", JsonNodeFactory.instance.objectNode().put("type", "integer"));

        ArrayNode required = JsonNodeFactory.instance.arrayNode();
        required.add("command");

        ObjectNode parameters = JsonNodeFactory.instance.objectNode();
        parameters.put("type", "object");
        parameters.set("properties", properties);
        parameters.set("required", required);
        parameters.put("additionalProperties", false);

        return new ToolDefinition(
                "execute_command",
                "在项目目录执行 Shell 命令（默认 60 秒超时，输出截断 8000 字符）",
                parameters,
                new ToolMetadata(ToolMetadata.RiskLevel.HIGH, false, true, false, "shell")
        );
    }

    private final WorkspacePathResolver resolver;

    public ExecuteCommandTool(WorkspacePathResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolOutput execute(JsonNode arguments, CancellationToken cancellationToken) throws Exception {
        if (cancellationToken != null && cancellationToken.isCancelled()) {
            return new ToolOutput("命令已被取消", JsonNodeFactory.instance.objectNode(), "");
        }

        if (arguments == null || !arguments.has("command")) {
            return new ToolOutput("Missing command parameter", JsonNodeFactory.instance.objectNode(), "");
        }
        String command = arguments.get("command").asText();
        if (command.isBlank()) {
            return new ToolOutput("命令不能为空", JsonNodeFactory.instance.objectNode(), "");
        }

        int timeoutSeconds = 60;
        if (arguments.has("timeout_seconds") && !arguments.get("timeout_seconds").isNull()) {
            timeoutSeconds = arguments.get("timeout_seconds").asInt();
        }
        if (timeoutSeconds <= 0 || timeoutSeconds > 300) {
            timeoutSeconds = Math.min(Math.max(timeoutSeconds, 1), 300);
        }

        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
        pb.directory(resolver.projectRoot().toFile());
        pb.redirectErrorStream(true);

        long startTime = System.currentTimeMillis();
        Process process;
        try {
            process = pb.start();
        } catch (Exception e) {
            return new ToolOutput("无法启动命令: " + e.getMessage(), JsonNodeFactory.instance.objectNode(), "");
        }

        if (cancellationToken != null) {
            cancellationToken.onCancel(process::destroyForcibly);
        }

        StringBuilder outputBuilder = new StringBuilder();
        Thread readerThread = new Thread(() -> {
            try (Reader reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                char[] buffer = new char[1024];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    synchronized (outputBuilder) {
                        if (outputBuilder.length() <= 8000) {
                            outputBuilder.append(buffer, 0, read);
                        }
                    }
                }
            } catch (Exception e) {
                // ignore
            }
        });
        readerThread.setDaemon(true);
        readerThread.start();

        boolean finished = false;
        long endTime = startTime + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < endTime) {
            if (cancellationToken != null && cancellationToken.isCancelled()) {
                process.destroyForcibly();
                return new ToolOutput("命令已被取消", JsonNodeFactory.instance.objectNode(), "");
            }
            try {
                if (process.waitFor(100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    finished = true;
                    break;
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                return new ToolOutput("命令执行被中断", JsonNodeFactory.instance.objectNode(), "");
            }
        }

        if (cancellationToken != null && cancellationToken.isCancelled()) {
            process.destroyForcibly();
            return new ToolOutput("命令已被取消", JsonNodeFactory.instance.objectNode(), "");
        }

        if (!finished) {
            process.destroyForcibly();
            return new ToolOutput("命令执行超时 (" + timeoutSeconds + "秒)", JsonNodeFactory.instance.objectNode(), "");
        }

        readerThread.join(1000);

        if (cancellationToken != null && cancellationToken.isCancelled()) {
            return new ToolOutput("命令已被取消", JsonNodeFactory.instance.objectNode(), "");
        }

        int exitCode = process.exitValue();
        
        String outputStr;
        synchronized (outputBuilder) {
            outputStr = outputBuilder.toString();
        }
        
        String truncatedOutput = outputStr;
        if (outputStr.length() > 8000) {
            truncatedOutput = outputStr.substring(0, 8000) + "\n...(输出已截断)";
        }
        
        String summary = "命令执行完成 (exit code: " + exitCode + ")\n" + truncatedOutput;
        return new ToolOutput(summary, JsonNodeFactory.instance.objectNode(), "");
    }
}
