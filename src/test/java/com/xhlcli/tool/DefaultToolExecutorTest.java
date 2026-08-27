package com.xhlcli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.ToolCall;
import com.xhlcli.model.ToolDefinition;
import com.xhlcli.model.ToolMetadata;
import com.xhlcli.model.ToolOutput;
import com.xhlcli.model.ToolResult;
import com.xhlcli.model.ToolResultStatus;
import com.xhlcli.tool.demo.EchoTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultToolExecutorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void returnsUnknownValidationAndCancellationResultsWithoutThrowing() {
        ToolExecutor executor = executor(new EchoTool(), 8_000, nanos(100, 100));
        CancellationToken token = new CancellationToken();

        ToolResult unknown = executor.execute(new ToolCall("call_1", "missing_tool", "{}"), token);
        ToolResult invalid = executor.execute(new ToolCall("call_2", "echo_text", "{not-json}"), token);
        ToolResult nullArguments = executor.execute(new ToolCall("call_4", "echo_text", null), token);
        token.cancel();
        ToolResult cancelled = executor.execute(new ToolCall("call_3", "echo_text", "{\"text\":\"x\"}"), token);

        assertEquals(ToolResultStatus.UNKNOWN_TOOL, unknown.status());
        assertEquals(ToolResultStatus.VALIDATION_ERROR, invalid.status());
        assertEquals(ToolResultStatus.VALIDATION_ERROR, nullArguments.status());
        assertEquals(ToolResultStatus.CANCELLED, cancelled.status());
    }

    @Test
    void convertsToolExceptionsToExecutionErrors() {
        ToolExecutor executor = executor(throwingTool(), 8_000, nanos(100, 200));

        ToolResult result = executor.execute(new ToolCall("call_1", "throwing_tool", "{}"), new CancellationToken());

        assertEquals(ToolResultStatus.EXECUTION_ERROR, result.status());
        assertEquals("Tool execution failed.", result.summary());
        assertEquals(0, result.elapsedMillis());
    }

    @Test
    void truncatesLargeResultsIntoAValidObservationWithMetadataAndContinueHint() throws Exception {
        ToolExecutor executor = executor(new EchoTool(), 180, nanos(100, 200));
        String text = "x".repeat(1_000);

        ToolResult result = executor.execute(
                new ToolCall("call_1", "echo_text", mapper.writeValueAsString(mapper.createObjectNode().put("text", text))),
                new CancellationToken());
        JsonNode observation = mapper.readTree(result.observationJson(mapper));

        assertTrue(observation.isObject());
        assertTrue(result.truncated());
        assertTrue(result.originalChars() > result.observationJson(mapper).length());
        assertTrue(result.observationJson(mapper).length() <= 180);
        assertTrue(result.continueHint().contains("narrower"));
    }

    @Test
    void clampsUnexpectedBackwardMonotonicTimeToZero() {
        ToolExecutor executor = executor(new EchoTool(), 8_000, nanos(200, 100));

        ToolResult result = executor.execute(
                new ToolCall("call_1", "echo_text", "{\"text\":\"x\"}"), new CancellationToken());

        assertEquals(0, result.elapsedMillis());
    }

    private ToolExecutor executor(Tool tool, int budget, LongSupplier nanos) {
        return new DefaultToolExecutor(
                new ToolRegistry(List.of(tool)),
                new ToolSchemaValidator(mapper),
                new ToolResultBudget(budget, mapper),
                mapper,
                nanos);
    }

    private Tool throwingTool() {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(
                        "throwing_tool",
                        "Always throws.",
                        mapper.createObjectNode().put("type", "object").put("additionalProperties", false),
                        ToolMetadata.conservative());
            }

            @Override
            public ToolOutput execute(JsonNode arguments, CancellationToken cancellationToken) {
                throw new IllegalStateException("boom");
            }
        };
    }

    private static LongSupplier nanos(long... values) {
        AtomicInteger index = new AtomicInteger();
        return () -> values[Math.min(index.getAndIncrement(), values.length - 1)];
    }

}
