package com.xhlcli.prompt;

import com.xhlcli.config.SecretRedactor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 系统提示词审计导出器：格式化输出分层全貌、来源标记、截断状态，并执行全量敏感凭据脱敏。
 */
public final class PromptExporter {
    private static final Pattern SENSITIVE_KEY_PATTERN = Pattern.compile(
            "(?i)(api[-_]?key|secret|token|password|bearer)\\s*[:=]\\s*['\"]?([a-zA-Z0-9\\-_.~+/]{12,})['\"]?"
    );

    private PromptExporter() {}

    /**
     * 将提示词分层结构导出为带审计注释且脱敏的完整文本。
     *
     * @param blocks 分层提示块列表
     * @param apiKey 需要显式脱敏的主 API Key
     * @return 格式化脱敏文本
     */
    public static String exportToString(List<PromptBlock> blocks, String apiKey) {
        Objects.requireNonNull(blocks, "blocks");

        StringBuilder sb = new StringBuilder();
        sb.append("# ================================================================================\n");
        sb.append("# XhlCLI Layered System Prompt Audit Dump\n");
        sb.append("# Exported At: ").append(Instant.now()).append("\n");
        sb.append("# Total Blocks: ").append(blocks.size()).append("\n");
        sb.append("# ================================================================================\n\n");

        for (PromptBlock block : blocks) {
            sb.append("# --------------------------------------------------------------------------------\n");
            sb.append(String.format(
                    "# [Layer %d: %s | Source: %s | Immutable: %s | Length: %d chars | Truncated: %s]\n",
                    block.layer().order(),
                    block.layer().displayName(),
                    block.source().description(),
                    block.layer().isImmutable(),
                    block.length(),
                    block.isTruncated()
            ));
            sb.append("# --------------------------------------------------------------------------------\n");

            String rendered = block.render();
            String redacted = redactSensitive(rendered, apiKey);
            sb.append(redacted).append("\n\n");
        }

        return sb.toString().trim();
    }

    /**
     * 将提示词分层导出写入指定文件。
     */
    public static Path exportToFile(List<PromptBlock> blocks, Path targetFile, String apiKey) throws IOException {
        Objects.requireNonNull(targetFile, "targetFile");
        String content = exportToString(blocks, apiKey);
        if (targetFile.getParent() != null) {
            Files.createDirectories(targetFile.getParent());
        }
        Files.writeString(targetFile, content, StandardCharsets.UTF_8);
        return targetFile;
    }

    /**
     * 深度脱敏：先由 SecretRedactor 替换核心 Key，再由正则替换疑似凭据。
     */
    public static String redactSensitive(String text, String apiKey) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String result = SecretRedactor.redact(text, apiKey);
        result = SENSITIVE_KEY_PATTERN.matcher(result).replaceAll("$1: [REDACTED_CREDENTIAL]");
        return result;
    }
}
