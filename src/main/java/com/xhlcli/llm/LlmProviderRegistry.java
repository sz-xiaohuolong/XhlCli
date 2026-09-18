package com.xhlcli.llm;

import com.xhlcli.config.ChatConfig;
import com.xhlcli.config.LogLevel;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM Provider 注册中心与模型工厂。
 * 统一管理各 Provider 的凭据探测、能力描述符、别名匹配与客户端实例创建。
 */
public final class LlmProviderRegistry {
    private final Map<String, String> environment;
    private final Map<String, ModelDescriptor> models = new LinkedHashMap<>();

    public LlmProviderRegistry(Map<String, String> environment) {
        this.environment = environment != null ? Map.copyOf(environment) : Map.of();
        registerDefaults();
    }

    private void registerDefaults() {
        // DeepSeek
        boolean deepseekConfigured = hasEnv("DEEPSEEK_API_KEY");
        register(new ModelDescriptor(
                "deepseek:deepseek-chat",
                "deepseek",
                "deepseek-chat",
                "DeepSeek Chat (128k, Tools, Cache)",
                ModelCapabilities.deepseekDefault(),
                deepseekConfigured,
                List.of("deepseek", "deepseek-chat", "deepseek-v4-flash")
        ));
        register(new ModelDescriptor(
                "deepseek:deepseek-coder",
                "deepseek",
                "deepseek-coder",
                "DeepSeek Coder (128k, Tools, Cache)",
                ModelCapabilities.deepseekDefault(),
                deepseekConfigured,
                List.of("deepseek-coder")
        ));
        register(new ModelDescriptor(
                "deepseek:deepseek-reasoner",
                "deepseek",
                "deepseek-reasoner",
                "DeepSeek Reasoner (128k, Reasoning)",
                new ModelCapabilities(128_000, true, false, true, "deepseek-prefix", true),
                deepseekConfigured,
                List.of("deepseek-reasoner")
        ));

        // OpenAI
        boolean openAiConfigured = hasEnv("OPENAI_API_KEY");
        register(new ModelDescriptor(
                "openai:gpt-4o",
                "openai",
                "gpt-4o",
                "OpenAI GPT-4o (128k, Tools, Vision)",
                ModelCapabilities.openAiDefault(),
                openAiConfigured,
                List.of("openai", "gpt-4o")
        ));
        register(new ModelDescriptor(
                "openai:gpt-4o-mini",
                "openai",
                "gpt-4o-mini",
                "OpenAI GPT-4o-mini (128k, Tools, Fast)",
                new ModelCapabilities(128_000, true, true, false, "none", false),
                openAiConfigured,
                List.of("gpt-4o-mini", "4o-mini")
        ));

        // Anthropic
        boolean anthropicConfigured = hasEnv("ANTHROPIC_API_KEY") || hasEnv("CLAUDE_API_KEY");
        register(new ModelDescriptor(
                "anthropic:claude-3-5-sonnet-20241022",
                "anthropic",
                "claude-3-5-sonnet-20241022",
                "Anthropic Claude 3.5 Sonnet (200k, Tools, Cache)",
                ModelCapabilities.claudeDefault(),
                anthropicConfigured,
                List.of("anthropic", "claude", "claude-3-5-sonnet", "sonnet")
        ));
        register(new ModelDescriptor(
                "anthropic:claude-3-5-haiku-20241022",
                "anthropic",
                "claude-3-5-haiku-20241022",
                "Anthropic Claude 3.5 Haiku (200k, Tools, Fast)",
                new ModelCapabilities(200_000, true, true, true, "anthropic-ephemeral", false),
                anthropicConfigured,
                List.of("claude-3-5-haiku", "claude-haiku", "haiku")
        ));

        // Ollama (Local)
        register(new ModelDescriptor(
                "ollama:qwen2.5-coder",
                "ollama",
                "qwen2.5-coder",
                "Ollama Qwen2.5-Coder (32k, Local)",
                ModelCapabilities.ollamaDefault(),
                true,
                List.of("ollama", "qwen2.5-coder", "qwen")
        ));
        register(new ModelDescriptor(
                "ollama:llama3.1",
                "ollama",
                "llama3.1",
                "Ollama Llama 3.1 (32k, Local)",
                ModelCapabilities.ollamaDefault(),
                true,
                List.of("llama3.1", "llama")
        ));
    }

    public synchronized void register(ModelDescriptor descriptor) {
        models.put(descriptor.id(), descriptor);
    }

    public synchronized List<ModelDescriptor> listModels() {
        return List.copyOf(models.values());
    }

    public synchronized Optional<ModelDescriptor> findModel(String query) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        for (ModelDescriptor desc : models.values()) {
            if (desc.matches(query)) {
                return Optional.of(desc);
            }
        }
        return Optional.empty();
    }

    public LlmClient createClient(String query, DiagnosticSink diagnostics) throws LlmException {
        ModelDescriptor desc = findModel(query).orElseThrow(() ->
                new LlmException(LlmErrorType.INVALID_CONFIGURATION,
                        "Unknown model or provider '" + query + "'. Use '/model list' to view available models.", false, false));
        return createClient(desc, diagnostics);
    }

    public LlmClient createClient(ModelDescriptor descriptor, DiagnosticSink diagnostics) throws LlmException {
        Objects.requireNonNull(descriptor, "descriptor");
        String provider = descriptor.provider().toLowerCase(Locale.ROOT);

        return switch (provider) {
            case "deepseek" -> createDeepSeekClient(descriptor, diagnostics);
            case "openai" -> createOpenAiClient(descriptor, diagnostics);
            case "anthropic" -> createAnthropicClient(descriptor, diagnostics);
            case "ollama" -> createOllamaClient(descriptor, diagnostics);
            default -> throw new LlmException(LlmErrorType.INVALID_CONFIGURATION,
                    "Unsupported provider: " + provider, false, false);
        };
    }

    private DeepSeekClient createDeepSeekClient(ModelDescriptor descriptor, DiagnosticSink diagnostics) throws LlmException {
        String apiKey = getEnv("DEEPSEEK_API_KEY");
        if (apiKey.isBlank()) {
            throw new LlmException(LlmErrorType.MISSING_CONFIGURATION,
                    "DEEPSEEK_API_KEY is not configured.", false, false);
        }
        String baseUrl = getEnvOrDefault("DEEPSEEK_BASE_URL", "https://api.deepseek.com");
        ChatConfig config = new ChatConfig(
                apiKey,
                descriptor.modelName(),
                URI.create(baseUrl),
                Duration.ofSeconds(30),
                Duration.ofSeconds(300),
                Duration.ofSeconds(600),
                LogLevel.INFO,
                Map.of()
        );
        return new DeepSeekClient(config, diagnostics);
    }

    private OpenAiClient createOpenAiClient(ModelDescriptor descriptor, DiagnosticSink diagnostics) throws LlmException {
        String apiKey = getEnv("OPENAI_API_KEY");
        if (apiKey.isBlank()) {
            throw new LlmException(LlmErrorType.MISSING_CONFIGURATION,
                    "OPENAI_API_KEY is not configured.", false, false);
        }
        String baseUrl = getEnvOrDefault("OPENAI_BASE_URL", "https://api.openai.com/v1");
        ChatConfig config = new ChatConfig(
                apiKey,
                descriptor.modelName(),
                URI.create(baseUrl),
                Duration.ofSeconds(30),
                Duration.ofSeconds(300),
                Duration.ofSeconds(600),
                LogLevel.INFO,
                Map.of()
        );
        return new OpenAiClient(config, diagnostics);
    }

    private AnthropicClaudeClient createAnthropicClient(ModelDescriptor descriptor, DiagnosticSink diagnostics) throws LlmException {
        String apiKey = getEnv("ANTHROPIC_API_KEY");
        if (apiKey.isBlank()) {
            apiKey = getEnv("CLAUDE_API_KEY");
        }
        if (apiKey.isBlank()) {
            throw new LlmException(LlmErrorType.MISSING_CONFIGURATION,
                    "ANTHROPIC_API_KEY is not configured.", false, false);
        }
        String baseUrl = getEnvOrDefault("ANTHROPIC_BASE_URL", AnthropicClaudeClient.DEFAULT_BASE_URL);
        ChatConfig config = new ChatConfig(
                apiKey,
                descriptor.modelName(),
                URI.create(baseUrl),
                Duration.ofSeconds(30),
                Duration.ofSeconds(300),
                Duration.ofSeconds(600),
                LogLevel.INFO,
                Map.of()
        );
        return new AnthropicClaudeClient(config, diagnostics);
    }

    private OllamaClient createOllamaClient(ModelDescriptor descriptor, DiagnosticSink diagnostics) {
        String baseUrl = getEnvOrDefault("OLLAMA_BASE_URL", OllamaClient.DEFAULT_BASE_URL);
        return new OllamaClient(descriptor.modelName(), URI.create(baseUrl), diagnostics);
    }

    private boolean hasEnv(String name) {
        String val = environment.get(name);
        return val != null && !val.isBlank();
    }

    private String getEnv(String name) {
        return environment.getOrDefault(name, "").trim();
    }

    private String getEnvOrDefault(String name, String defaultValue) {
        String val = environment.get(name);
        return (val != null && !val.isBlank()) ? val.trim() : defaultValue;
    }
}
