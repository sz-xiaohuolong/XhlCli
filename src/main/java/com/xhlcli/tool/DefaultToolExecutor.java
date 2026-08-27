package com.xhlcli.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.ToolCall;
import com.xhlcli.model.ToolOutput;
import com.xhlcli.model.ToolResult;
import com.xhlcli.model.ToolResultStatus;

import java.util.Objects;
import java.util.function.LongSupplier;

/** Executes one validated tool call and converts every expected failure into a ToolResult. */
public final class DefaultToolExecutor implements ToolExecutor {
    private final ToolRegistry registry;
    private final ToolSchemaValidator schemaValidator;
    private final ToolResultBudget resultBudget;
    private final ObjectMapper mapper;
    private final LongSupplier nanoTime;

    public DefaultToolExecutor(
            ToolRegistry registry,
            ToolSchemaValidator schemaValidator,
            ToolResultBudget resultBudget,
            ObjectMapper mapper,
            LongSupplier nanoTime) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.schemaValidator = Objects.requireNonNull(schemaValidator, "schemaValidator");
        this.resultBudget = Objects.requireNonNull(resultBudget, "resultBudget");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    public ToolResult execute(ToolCall call, CancellationToken cancellationToken) {
        long startedAt = nanoTime.getAsLong();
        String callId = call == null || call.id() == null ? "" : call.id();
        String toolName = call == null || call.name() == null ? "" : call.name();
        try {
            if (isCancelled(cancellationToken)) {
                return finish(startedAt, callId, toolName, ToolResultStatus.CANCELLED, "Tool execution was cancelled.");
            }
            if (callId.isBlank()) {
                return finish(startedAt, callId, toolName, ToolResultStatus.VALIDATION_ERROR, "Tool call ID must not be blank.");
            }
            if (toolName.isBlank()) {
                return finish(startedAt, callId, toolName, ToolResultStatus.VALIDATION_ERROR, "Tool name must not be blank.");
            }
            Tool tool = registry.find(toolName).orElse(null);
            if (tool == null) {
                return finish(startedAt, callId, toolName, ToolResultStatus.UNKNOWN_TOOL, "Unknown tool: " + toolName + ".");
            }
            ToolSchemaValidator.ValidationResult validation = schemaValidator.parseAndValidate(
                    call.argumentsJson(), tool.definition().parameters());
            if (!validation.isValid()) {
                return finish(startedAt, callId, toolName, ToolResultStatus.VALIDATION_ERROR, validation.error());
            }
            if (isCancelled(cancellationToken)) {
                return finish(startedAt, callId, toolName, ToolResultStatus.CANCELLED, "Tool execution was cancelled.");
            }
            ToolOutput output = tool.execute(validation.arguments(), cancellationToken);
            return finish(startedAt, callId, toolName, ToolResultStatus.SUCCESS, output.summary(), output.data(), output.continueHint());
        } catch (Exception failure) {
            return finish(startedAt, callId, toolName, ToolResultStatus.EXECUTION_ERROR, "Tool execution failed.");
        }
    }

    private ToolResult finish(long startedAt, String callId, String toolName, ToolResultStatus status, String summary) {
        return finish(startedAt, callId, toolName, status, summary, mapper.createObjectNode(), "");
    }

    private ToolResult finish(
            long startedAt, String callId, String toolName, ToolResultStatus status, String summary,
            com.fasterxml.jackson.databind.JsonNode data, String continueHint) {
        long elapsedMillis = elapsedMillis(startedAt);
        ToolResult result = new ToolResult(
                callId, toolName, status, summary, data, elapsedMillis, false, 0, continueHint);
        return resultBudget.apply(result);
    }

    private long elapsedMillis(long startedAt) {
        long endedAt = nanoTime.getAsLong();
        if (endedAt < startedAt) {
            return 0;
        }
        try {
            return Math.subtractExact(endedAt, startedAt) / 1_000_000L;
        } catch (ArithmeticException overflow) {
            return 0;
        }
    }

    private static boolean isCancelled(CancellationToken cancellationToken) {
        return cancellationToken != null && cancellationToken.isCancelled();
    }
}
