package com.xhlcli.llm;

import java.util.Objects;

/**
 * 模型能力声明元信息。
 * 显式声明各 Provider / 模型支持的特征与限制，杜绝向上层暴露 Provider 专有逻辑或向模型发送不兼容请求。
 */
public record ModelCapabilities(
        int maxContextWindow,
        boolean supportsTools,
        boolean supportsImageInput,
        boolean supportsPromptCaching,
        String promptCacheMode,
        boolean requiresReasoningEffort
) {
    public ModelCapabilities {
        if (maxContextWindow <= 0) {
            throw new IllegalArgumentException("maxContextWindow must be positive, got " + maxContextWindow);
        }
        promptCacheMode = Objects.requireNonNullElse(promptCacheMode, "none");
    }

    public boolean supportsVision() {
        return supportsImageInput;
    }

    public static ModelCapabilities deepseekDefault() {
        return new ModelCapabilities(
                128_000,
                true,
                false,
                true,
                "deepseek-prefix",
                false
        );
    }

    public static ModelCapabilities openAiDefault() {
        return new ModelCapabilities(
                128_000,
                true,
                true,
                false,
                "none",
                false
        );
    }

    public static ModelCapabilities claudeDefault() {
        return new ModelCapabilities(
                200_000,
                true,
                true,
                true,
                "anthropic-ephemeral",
                false
        );
    }

    public static ModelCapabilities ollamaDefault() {
        return new ModelCapabilities(
                32_768,
                true,
                false,
                false,
                "none",
                false
        );
    }

    public static ModelCapabilities textOnly(int contextWindow) {
        return new ModelCapabilities(
                contextWindow,
                false,
                false,
                false,
                "none",
                false
        );
    }
}
