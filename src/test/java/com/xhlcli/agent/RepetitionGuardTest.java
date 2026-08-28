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

    @Test
    void detectsTheThirdConsecutiveMalformedArgumentTextAndResetsWhenItChanges() throws Exception {
        RepetitionGuard guard = new RepetitionGuard(mapper);
        List<ToolResult> results = List.of(validationError("one"));

        assertFalse(guard.recordCompletedIteration(List.of(call("one", "{bad-json}")), results));
        assertFalse(guard.recordCompletedIteration(List.of(call("two", "{bad-json}")), results));
        assertTrue(guard.recordCompletedIteration(List.of(call("three", "{bad-json}")), results));

        assertFalse(guard.recordCompletedIteration(List.of(call("four", "{different-json}")), results));
        assertFalse(guard.recordCompletedIteration(List.of(call("five", "{bad-json}")), results));
        assertFalse(guard.recordCompletedIteration(List.of(call("six", "{bad-json}")), results));
        assertTrue(guard.recordCompletedIteration(List.of(call("seven", "{bad-json}")), results));
    }

    @Test
    void keepsValidJsonAndMalformedRawArgumentTextInSeparateFingerprintDomains() throws Exception {
        RepetitionGuard guard = new RepetitionGuard(mapper);
        List<ToolResult> results = List.of(validationError("one"));

        assertFalse(guard.recordCompletedIteration(
                List.of(call("one", "{\"raw\":\"{bad-json}\"}")), results));
        assertFalse(guard.recordCompletedIteration(List.of(call("two", "{bad-json}")), results));
        assertFalse(guard.recordCompletedIteration(List.of(call("three", "{bad-json}")), results));
        assertTrue(guard.recordCompletedIteration(List.of(call("four", "{bad-json}")), results));
    }

    @Test
    void treatsEmptyInputAsMalformedAndKeepsItSeparateFromJsonNull() throws Exception {
        RepetitionGuard guard = new RepetitionGuard(mapper);
        List<ToolResult> results = List.of(validationError("one"));

        assertFalse(guard.recordCompletedIteration(List.of(call("one", "")), results));
        assertFalse(guard.recordCompletedIteration(List.of(call("two", "null")), results));
        assertFalse(guard.recordCompletedIteration(List.of(call("three", "null")), results));
    }

    @Test
    void treatsTrailingTokensAsMalformedRawTextAndRepeatsOnlyTheExactText() throws Exception {
        RepetitionGuard guard = new RepetitionGuard(mapper);
        List<ToolResult> results = List.of(validationError("one"));
        String valid = "{\"a\":1}";
        String trailing = "{\"a\":1}junk";

        assertFalse(guard.recordCompletedIteration(List.of(call("one", valid)), results));
        assertFalse(guard.recordCompletedIteration(List.of(call("two", trailing)), results));
        assertFalse(guard.recordCompletedIteration(List.of(call("three", trailing)), results));
        assertTrue(guard.recordCompletedIteration(List.of(call("four", trailing)), results));
    }

    @Test
    void keepsNullRawStableWithoutCollidingWithJsonNull() throws Exception {
        RepetitionGuard guard = new RepetitionGuard(mapper);
        List<ToolResult> results = List.of(validationError("one"));

        assertFalse(guard.recordCompletedIteration(List.of(call("one", null)), results));
        assertFalse(guard.recordCompletedIteration(List.of(call("two", "null")), results));
        assertFalse(guard.recordCompletedIteration(List.of(call("three", null)), results));
        assertFalse(guard.recordCompletedIteration(List.of(call("four", null)), results));
        assertTrue(guard.recordCompletedIteration(List.of(call("five", null)), results));
    }

    private ToolCall call(String id, String arguments) {
        return new ToolCall(id, "echo_text", arguments);
    }

    private ToolResult validationError(String callId) throws Exception {
        return new ToolResult(
                callId,
                "echo_text",
                ToolResultStatus.VALIDATION_ERROR,
                "invalid",
                mapper.readTree("{}"),
                0,
                false,
                2,
                "");
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
