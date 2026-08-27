package com.xhlcli.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Objects;

/** Validates the deliberately small JSON Schema subset supported by Phase 02 tools. */
public final class ToolSchemaValidator {
    private final ObjectMapper mapper;

    public ToolSchemaValidator(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public ValidationResult parseAndValidate(String argumentsJson, JsonNode schema) {
        if (argumentsJson == null) {
            return ValidationResult.error("Arguments must be valid JSON.");
        }
        final JsonNode parsed;
        try {
            parsed = mapper.readTree(argumentsJson);
        } catch (JsonProcessingException failure) {
            return ValidationResult.error("Arguments must be valid JSON.");
        }
        if (!parsed.isObject()) {
            return ValidationResult.error("Arguments must be a JSON object.");
        }
        if (schema == null || !schema.isObject() || !"object".equals(schema.path("type").asText())) {
            return ValidationResult.error("Tool schema must have an object root.");
        }

        ObjectNode arguments = (ObjectNode) parsed;
        JsonNode required = schema.path("required");
        if (required.isArray()) {
            for (JsonNode requiredName : required) {
                String property = requiredName.asText();
                if (!arguments.has(property)) {
                    return ValidationResult.error("Missing required property: " + property + ".");
                }
            }
        }

        JsonNode properties = schema.path("properties");
        Iterator<String> names = arguments.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            JsonNode propertySchema = properties.path(name);
            if (propertySchema.isMissingNode()) {
                if (schema.path("additionalProperties").isBoolean()
                        && !schema.path("additionalProperties").booleanValue()) {
                    return ValidationResult.error("Unknown property: " + name + ".");
                }
                continue;
            }
            String typeError = typeError(name, arguments.path(name), propertySchema.path("type").asText());
            if (typeError != null) {
                return ValidationResult.error(typeError);
            }
        }
        return ValidationResult.valid(arguments.deepCopy());
    }

    private static String typeError(String property, JsonNode value, String type) {
        if (!isSupportedType(type)) {
            return "Property '" + property + "' has an unsupported or missing schema type.";
        }
        boolean valid = switch (type) {
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "array" -> value.isArray();
            case "object" -> value.isObject();
            default -> throw new IllegalStateException("Unsupported schema type: " + type);
        };
        String article = switch (type) {
            case "integer", "object", "array" -> "an";
            default -> "a";
        };
        return valid ? null : "Property '" + property + "' must be " + article + " " + type + ".";
    }

    private static boolean isSupportedType(String type) {
        return switch (type) {
            case "string", "integer", "number", "boolean", "array", "object" -> true;
            default -> false;
        };
    }

    public record ValidationResult(ObjectNode arguments, String error) {
        public ValidationResult {
            if ((arguments == null) == (error == null)) {
                throw new IllegalArgumentException("Exactly one validation result value is required.");
            }
            arguments = arguments == null ? null : arguments.deepCopy();
        }

        static ValidationResult valid(ObjectNode arguments) {
            return new ValidationResult(arguments, null);
        }

        static ValidationResult error(String error) {
            return new ValidationResult(null, error);
        }

        public boolean isValid() {
            return arguments != null;
        }

        @Override
        public ObjectNode arguments() {
            return arguments == null ? null : arguments.deepCopy();
        }
    }
}
