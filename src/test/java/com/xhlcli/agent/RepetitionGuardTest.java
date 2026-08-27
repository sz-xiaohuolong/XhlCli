package com.xhlcli.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.model.ToolCall;
import com.xhlcli.model.ToolResult;
import com.xhlcli.model.ToolResultStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepetitionGuardTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void reportsOnlyTheThirdConsecutiveEquivalentCompletedIteration() throws Exception {
        RepetitionGuard guard = new RepetitionGuard(mapper);

        List<ToolCall> firstCalls = List.of(new ToolCall("call_one", "echo_text", "{\"a\":1,\"b\":[\"x\",\"y\"]}"));
        List<ToolCall> reorderedCalls = List.of(new ToolCall("call_two", "echo_text", "{\"b\":[\"x\",\"y\"],\"a\":1}"));
        List<ToolResult> firstResults = List.of(result("call_one", "{\"first\":true,\"nested\":{\"a\":1,\"b\":2}}", 1));
        List<ToolResult> reorderedResults = List.of(result("call_two", "{\"nested\":{\"b\":2,\"a\":1},\"first\":true}", 999));

        assertFalse(guard.recordCompletedIteration(firstCalls, firstResults));
        assertFalse(guard.recordCompletedIteration(reorderedCalls, reorderedResults));
        assertTrue(guard.recordCompletedIteration(firstCalls, firstResults));
    }

    @Test
    void resetsTheConsecutiveCountWhenTheResultDataChanges() throws Exception {
        RepetitionGuard guard = new RepetitionGuard(mapper);
        List<ToolCall> calls = List.of(new ToolCall("call_one", "echo_text", "{\"text\":\"hello\"}"));
        List<ToolResult> firstResult = List.of(result("call_one", "{\"text\":\"first\"}", 1));
        List<ToolResult> changedResult = List.of(result("call_two", "{\"text\":\"second\"}", 2));

        assertFalse(guard.recordCompletedIteration(calls, firstResult));
        assertFalse(guard.recordCompletedIteration(calls, firstResult));
        assertFalse(guard.recordCompletedIteration(calls, changedResult));
        assertFalse(guard.recordCompletedIteration(calls, firstResult));
        assertFalse(guard.recordCompletedIteration(calls, firstResult));
        assertTrue(guard.recordCompletedIteration(calls, firstResult));
    }

    private ToolResult result(String callId, String data, long elapsedMillis) throws Exception {
        return new ToolResult(
                callId,
                "echo_text",
                ToolResultStatus.SUCCESS,
                "echoed",
                mapper.readTree(data),
                elapsedMillis,
                false,
                data.length(),
                "");
    }
}
