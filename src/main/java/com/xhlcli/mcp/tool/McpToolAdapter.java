package com.xhlcli.mcp.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.mcp.client.McpClient;
import com.xhlcli.mcp.model.McpCallResult;
import com.xhlcli.mcp.model.McpContent;
import com.xhlcli.mcp.model.McpToolDefinition;
import com.xhlcli.model.ToolDefinition;
import com.xhlcli.model.ToolMetadata;
import com.xhlcli.model.ToolOutput;
import com.xhlcli.tool.Tool;

import java.time.Duration;
import java.util.Objects;

/**
 * Adapter converting an MCP tool into an in-process Tool registered in ToolRegistry.
 */
public final class McpToolAdapter implements Tool {

    private final String serverName;
    private final String rawToolName;
    private final McpClient client;
    private final ToolDefinition toolDefinition;
    private final Duration executionTimeout;
    private final ObjectMapper mapper;

    public McpToolAdapter(String serverName, McpToolDefinition mcpTool, McpClient client, ObjectMapper mapper, Duration executionTimeout, boolean trustedReadOnly) {
        this.serverName = Objects.requireNonNull(serverName, "serverName");
        Objects.requireNonNull(mcpTool, "mcpTool");
        this.rawToolName = mcpTool.name();
        this.client = Objects.requireNonNull(client, "client");
        this.executionTimeout = executionTimeout != null ? executionTimeout : Duration.ofSeconds(60);
        this.mapper = mapper != null ? mapper : new ObjectMapper();

        String qualifiedName = normalizeToolName(serverName, mcpTool.name());
        String description = mcpTool.description() != null && !mcpTool.description().isBlank()
                ? mcpTool.description()
                : "MCP tool " + mcpTool.name() + " from server " + serverName;
        JsonNode sanitizedParameters = sanitizeSchema(mcpTool.inputSchema(), this.mapper);

        ToolMetadata.RiskLevel riskLevel = trustedReadOnly ? ToolMetadata.RiskLevel.LOW : ToolMetadata.RiskLevel.HIGH;
        ToolMetadata metadata = new ToolMetadata(riskLevel, trustedReadOnly, true, false, "mcp:" + serverName);

        this.toolDefinition = new ToolDefinition(qualifiedName, description, sanitizedParameters, metadata);
    }

    public String serverName() {
        return serverName;
    }

    public String rawToolName() {
        return rawToolName;
    }

    @Override
    public ToolDefinition definition() {
        return toolDefinition;
    }

    @Override
    public ToolOutput execute(JsonNode arguments, CancellationToken cancellationToken) throws Exception {
        McpCallResult result = client.callTool(rawToolName, arguments, cancellationToken, executionTimeout);

        StringBuilder output = new StringBuilder();
        for (McpContent content : result.contents()) {
            if ("text".equals(content.type())) {
                if (content.text() != null) {
                    output.append(content.text()).append("\n");
                }
            } else if ("image".equals(content.type())) {
                int sizeBytes = content.data() != null ? content.data().length() : 0;
                output.append(String.format("[Image: mimeType=%s, size=%d bytes]\n", content.mimeType(), sizeBytes));
            } else if ("resource".equals(content.type()) && content.resource() != null) {
                output.append(String.format("[Resource: %s]\n", content.resource().uri()));
            }
        }

        String finalOutput = output.toString().trim();
        if (finalOutput.isEmpty()) {
            finalOutput = result.isError() ? "MCP tool execution failed." : "Tool execution succeeded with empty response.";
        }
        return new ToolOutput(finalOutput, mapper.createObjectNode(), result.isError() ? "error" : "");
    }

    public static String normalizeToolName(String serverName, String rawToolName) {
        String cleanServer = serverName.toLowerCase().replaceAll("[^a-z0-9_]", "_");
        String cleanTool = rawToolName.toLowerCase().replaceAll("[^a-z0-9_]", "_");
        return "mcp__" + cleanServer + "__" + cleanTool;
    }

    public static JsonNode sanitizeSchema(JsonNode inputSchema, ObjectMapper mapper) {
        if (inputSchema == null || !inputSchema.isObject()) {
            ObjectNode defaultSchema = mapper.createObjectNode();
            defaultSchema.put("type", "object");
            defaultSchema.putObject("properties");
            return defaultSchema;
        }

        ObjectNode schema = (ObjectNode) inputSchema.deepCopy();
        if (!"object".equals(schema.path("type").asText())) {
            schema.put("type", "object");
        }
        if (!schema.has("properties") || !schema.path("properties").isObject()) {
            schema.putObject("properties");
        }

        schema.remove("$schema");
        schema.remove("$id");
        return schema;
    }
}
