package com.xhlcli.skill;

import com.xhlcli.prompt.PromptSource;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 代表一个已解析的完整 Skill 实体。
 */
public final class SkillDefinition {
    private final SkillMetadata metadata;
    private final PromptSource source;
    private final Path directoryPath;
    private final Path skillMdPath;
    private final String instructions;
    private final String parseError;
    private volatile boolean isEnabled;

    public SkillDefinition(
            SkillMetadata metadata,
            PromptSource source,
            Path directoryPath,
            Path skillMdPath,
            String instructions,
            String parseError,
            boolean isEnabled) {
        this.metadata = metadata != null ? metadata : SkillMetadata.of("", "");
        this.source = Objects.requireNonNull(source, "source");
        this.directoryPath = directoryPath;
        this.skillMdPath = skillMdPath;
        this.instructions = instructions != null ? instructions.trim() : "";
        this.parseError = parseError;
        this.isEnabled = isEnabled;
    }

    public static SkillDefinition healthy(
            SkillMetadata metadata,
            PromptSource source,
            Path directoryPath,
            Path skillMdPath,
            String instructions) {
        return new SkillDefinition(metadata, source, directoryPath, skillMdPath, instructions, null, true);
    }

    public static SkillDefinition error(
            String name,
            PromptSource source,
            Path directoryPath,
            Path skillMdPath,
            String parseError) {
        SkillMetadata meta = SkillMetadata.of(name != null ? name : "unknown", "Parse error: " + parseError);
        return new SkillDefinition(meta, source, directoryPath, skillMdPath, "", parseError, false);
    }

    public SkillMetadata metadata() {
        return metadata;
    }

    public String name() {
        return metadata.name();
    }

    public String description() {
        return metadata.description();
    }

    public PromptSource source() {
        return source;
    }

    public Path directoryPath() {
        return directoryPath;
    }

    public Path skillMdPath() {
        return skillMdPath;
    }

    public String instructions() {
        return instructions;
    }

    public String parseError() {
        return parseError;
    }

    public boolean isHealthy() {
        return parseError == null;
    }

    public boolean isEnabled() {
        return isEnabled && isHealthy();
    }

    public void setEnabled(boolean enabled) {
        this.isEnabled = enabled;
    }

    @Override
    public String toString() {
        return "SkillDefinition{" +
                "name='" + name() + '\'' +
                ", source=" + source +
                ", healthy=" + isHealthy() +
                ", enabled=" + isEnabled() +
                '}';
    }
}
