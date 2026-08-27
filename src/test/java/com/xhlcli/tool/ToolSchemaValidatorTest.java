package com.xhlcli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolSchemaValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolSchemaValidator validator = new ToolSchemaValidator(mapper);

    @Test
    void acceptsAValidObjectThatMatchesSupportedPropertyTypes() throws Exception {
        ToolSchemaValidator.ValidationResult result = validator.parseAndValidate(
                """
                {"text":"hello","count":2,"ratio":2.5,"enabled":true,"items":[1],"nested":{"key":"value"}}
                """,
                schema());

        assertTrue(result.isValid());
        assertEquals("hello", result.arguments().path("text").asText());
    }

    @Test
    void reportsMalformedJsonAndNonObjectRootsSafely() {
        ToolSchemaValidator.ValidationResult malformed = validator.parseAndValidate("{not-json}", schema());
        ToolSchemaValidator.ValidationResult nullArguments = validator.parseAndValidate(null, schema());
        ToolSchemaValidator.ValidationResult scalar = validator.parseAndValidate("\"text\"", schema());
        ToolSchemaValidator.ValidationResult array = validator.parseAndValidate("[]", schema());

        assertFalse(malformed.isValid());
        assertEquals("Arguments must be valid JSON.", malformed.error());
        assertFalse(nullArguments.isValid());
        assertEquals("Arguments must be valid JSON.", nullArguments.error());
        assertFalse(scalar.isValid());
        assertEquals("Arguments must be a JSON object.", scalar.error());
        assertFalse(array.isValid());
        assertEquals("Arguments must be a JSON object.", array.error());
    }

    @Test
    void reportsMissingRequiredFieldsWrongTypesAndForbiddenUnknownFields() {
        assertError("{}", "Missing required property: text.");
        assertError("{\"text\":1}", "Property 'text' must be a string.");
        assertError("{\"text\":\"x\",\"count\":2.5}", "Property 'count' must be an integer.");
        assertError("{\"text\":\"x\",\"ratio\":false}", "Property 'ratio' must be a number.");
        assertError("{\"text\":\"x\",\"enabled\":\"yes\"}", "Property 'enabled' must be a boolean.");
        assertError("{\"text\":\"x\",\"items\":{}}", "Property 'items' must be an array.");
        assertError("{\"text\":\"x\",\"nested\":[]}", "Property 'nested' must be an object.");
        assertError("{\"text\":\"x\",\"extra\":true}", "Unknown property: extra.");
        assertError("{\"text\":\"x\",\"unsupported\":true}", "Property 'unsupported' has an unsupported or missing schema type.");
        assertError("{\"text\":\"x\",\"missing\":true}", "Property 'missing' has an unsupported or missing schema type.");
    }

    @Test
    void allowsUnknownPropertiesWhenAdditionalPropertiesIsOmittedOrTrue() {
        ObjectNode omitted = (ObjectNode) schema();
        omitted.remove("additionalProperties");
        ObjectNode allowed = (ObjectNode) schema();
        allowed.put("additionalProperties", true);

        assertTrue(validator.parseAndValidate("{\"text\":\"x\",\"extra\":true}", omitted).isValid());
        assertTrue(validator.parseAndValidate("{\"text\":\"x\",\"extra\":true}", allowed).isValid());
    }

    private void assertError(String arguments, String error) {
        ToolSchemaValidator.ValidationResult result = validator.parseAndValidate(arguments, schema());

        assertFalse(result.isValid());
        assertEquals(error, result.error());
    }

    private JsonNode schema() {
        ObjectNode properties = mapper.createObjectNode();
        properties.set("text", property("string"));
        properties.set("count", property("integer"));
        properties.set("ratio", property("number"));
        properties.set("enabled", property("boolean"));
        properties.set("items", property("array"));
        properties.set("nested", property("object"));
        properties.set("unsupported", property("date"));
        properties.set("missing", mapper.createObjectNode());
        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        schema.set("properties", properties);
        schema.set("required", mapper.createArrayNode().add("text"));
        schema.put("additionalProperties", false);
        return schema;
    }

    private ObjectNode property(String type) {
        return mapper.createObjectNode().put("type", type);
    }
}
