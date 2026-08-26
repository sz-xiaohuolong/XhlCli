package com.xhlcli.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretRedactorTest {

    @Test
    void removesKnownKeyBearerTokensAndCredentialValues() {
        String key = "real-deepseek-secret-123";
        String input = """
                key=real-deepseek-secret-123 Authorization: Bearer real-deepseek-secret-123
                {"api_key":"api-secret","apiKey":"camel-secret","authorization":"Bearer auth-secret",
                 "token":"token-secret","password":"password-secret"}
                """;

        String redacted = SecretRedactor.redact(input, key);

        assertFalse(redacted.contains(key));
        assertFalse(redacted.contains("api-secret"));
        assertFalse(redacted.contains("camel-secret"));
        assertFalse(redacted.contains("auth-secret"));
        assertFalse(redacted.contains("token-secret"));
        assertFalse(redacted.contains("password-secret"));
        assertTrue(redacted.contains("***"));
    }

    @Test
    void preservesSafeDiagnostics() {
        String input = "provider=deepseek model=deepseek-v4-flash status=429";

        assertTrue(SecretRedactor.redact(input, null).contains(input));
    }
}
