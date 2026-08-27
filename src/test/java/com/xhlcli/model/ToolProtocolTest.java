package com.xhlcli.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolProtocolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void conservativeMetadataIsHighRiskAndDefinitionsDefensivelyCopySchema() {
        ToolMetadata metadata = ToolMetadata.conservative();
        ObjectNode parameters = mapper.createObjectNode().put("type", "object");
        ToolDefinition definition = new ToolDefinition("echo_text", "Echo text", parameters, metadata);

        parameters.put("type", "array");
        ((ObjectNode) definition.parameters()).put("additionalProperties", false);

        assertEquals(ToolMetadata.RiskLevel.HIGH, metadata.riskLevel());
        assertFalse(metadata.readOnly());
        assertFalse(metadata.cancellable());
        assertFalse(metadata.allowsParallel());
        assertEquals("object", definition.parameters().path("type").asText());
        assertFalse(definition.parameters().has("additionalProperties"));
    }

    @Test
    void observationJsonUsesTheStableEnvelopeAndFieldOrder() throws Exception {
        ObjectNode data = mapper.createObjectNode().put("text", "hello");
        ToolResult result = new ToolResult(
                "call_1",
                "echo_text",
                ToolResultStatus.SUCCESS,
                "Echoed 5 characters.",
                data,
                12,
                false,
                16,
                "");

        String observation = result.observationJson(mapper);

        assertEquals(
                "{\"status\":\"success\",\"summary\":\"Echoed 5 characters.\","
                        + "\"data\":{\"text\":\"hello\"},\"elapsed_ms\":12,\"truncated\":false,"
                        + "\"original_chars\":16,\"continue_hint\":\"\"}",
                observation);
        assertTrue(mapper.readTree(observation).isObject());
    }
}
