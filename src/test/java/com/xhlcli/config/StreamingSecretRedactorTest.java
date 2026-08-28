package com.xhlcli.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingSecretRedactorTest {

    @Test
    void preservesOrdinaryUnicodeAndStreamsBeforeCompletion() {
        String source = "ordinary text 你好 ".repeat(8);
        StreamingSecretRedactor redactor = new StreamingSecretRedactor(null);

        String first = redactor.accept(source);
        String rendered = first + redactor.finish();

        assertFalse(first.isEmpty());
        assertEquals(source, rendered);
    }

    @Test
    void redactsKnownApiKeyAcrossEveryPossibleTwoChunkSplit() {
        String key = "sk-known-secret-123";
        String source = "before " + key + " after";

        for (int split = 0; split <= source.length(); split++) {
            StreamingSecretRedactor redactor = new StreamingSecretRedactor(key);
            String rendered = redactor.accept(source.substring(0, split))
                    + redactor.accept(source.substring(split))
                    + redactor.finish();

            assertFalse(rendered.contains(key), "split=" + split);
            assertTrue(rendered.contains("***"), "split=" + split);
        }
    }

    @Test
    void redactsBearerAndCredentialFieldsWhenTheirValuesSpanChunks() {
        assertRedactedAcrossAllSplits("Bearer provider-token, next", "provider-token");
        assertRedactedAcrossAllSplits("{\"token\":\"provider-token\"}", "provider-token");
        assertRedactedAcrossAllSplits("password=provider-token next", "provider-token");
        assertRedactedAcrossAllSplits("authorization = Bearer provider-token }", "provider-token");
        assertRedactedAcrossAllSplits("token" + " ".repeat(100) + "=provider-token next", "provider-token");
        assertRedactedAcrossAllSplits("prefix {\"token\":\"provider-token", "provider-token");
    }

    private static void assertRedactedAcrossAllSplits(String source, String secret) {
        for (int split = 0; split <= source.length(); split++) {
            StreamingSecretRedactor redactor = new StreamingSecretRedactor(null);
            String rendered = redactor.accept(source.substring(0, split))
                    + redactor.accept(source.substring(split))
                    + redactor.finish();

            assertFalse(rendered.contains(secret), "split=" + split + ", source=" + source);
            assertTrue(rendered.contains("***"), "split=" + split + ", source=" + source);
        }
    }
}
