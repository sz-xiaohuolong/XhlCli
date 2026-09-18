package com.xhlcli.llm;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class LlmProviderRegistryTest {

    @Test
    void listsDefaultModelsWithCorrectCapabilities() {
        Map<String, String> env = Map.of(
                "DEEPSEEK_API_KEY", "sk-deepseek",
                "OPENAI_API_KEY", "sk-openai"
        );
        LlmProviderRegistry registry = new LlmProviderRegistry(env);
        var models = registry.listModels();

        assertTrue(models.size() >= 8);

        // Verify DeepSeek
        ModelDescriptor deepseek = registry.findModel("deepseek").orElseThrow();
        assertEquals("deepseek:deepseek-chat", deepseek.id());
        assertTrue(deepseek.configured());
        assertTrue(deepseek.capabilities().supportsTools());
        assertTrue(deepseek.capabilities().supportsPromptCaching());
        assertEquals(128_000, deepseek.capabilities().maxContextWindow());

        // Verify OpenAI
        ModelDescriptor gpt4o = registry.findModel("gpt-4o").orElseThrow();
        assertEquals("openai:gpt-4o", gpt4o.id());
        assertTrue(gpt4o.configured());
        assertTrue(gpt4o.capabilities().supportsVision());

        // Verify Anthropic (missing key in env)
        ModelDescriptor claude = registry.findModel("claude-3-5-sonnet").orElseThrow();
        assertEquals("anthropic:claude-3-5-sonnet-20241022", claude.id());
        assertFalse(claude.configured());
        assertEquals(200_000, claude.capabilities().maxContextWindow());

        // Verify Ollama (local)
        ModelDescriptor ollama = registry.findModel("qwen2.5-coder").orElseThrow();
        assertEquals("ollama:qwen2.5-coder", ollama.id());
        assertTrue(ollama.configured());
    }

    @Test
    void findsModelByAlias() {
        LlmProviderRegistry registry = new LlmProviderRegistry(Map.of());

        assertTrue(registry.findModel("4o-mini").isPresent());
        assertTrue(registry.findModel("claude").isPresent());
        assertTrue(registry.findModel("deepseek-v4-flash").isPresent());
        assertTrue(registry.findModel("llama3.1").isPresent());
        assertFalse(registry.findModel("non-existent-model").isPresent());
    }

    @Test
    void createsClientWhenKeyConfiguredAndFailsWhenMissing() {
        Map<String, String> env = Map.of(
                "DEEPSEEK_API_KEY", "sk-deepseek-123"
        );
        LlmProviderRegistry registry = new LlmProviderRegistry(env);

        // DeepSeek succeeds
        assertDoesNotThrow(() -> {
            LlmClient client = registry.createClient("deepseek", DiagnosticSink.NO_OP);
            assertEquals("deepseek", client.providerName());
            assertEquals("deepseek-chat", client.modelName());
        });

        // Anthropic fails due to missing key
        LlmException ex = assertThrows(LlmException.class, () ->
                registry.createClient("claude", DiagnosticSink.NO_OP));
        assertEquals(LlmErrorType.MISSING_CONFIGURATION, ex.type());
        assertTrue(ex.getMessage().contains("ANTHROPIC_API_KEY"));

        // Ollama succeeds (no key needed)
        assertDoesNotThrow(() -> {
            LlmClient client = registry.createClient("ollama", DiagnosticSink.NO_OP);
            assertEquals("ollama", client.providerName());
            assertEquals("qwen2.5-coder", client.modelName());
        });
    }
}
