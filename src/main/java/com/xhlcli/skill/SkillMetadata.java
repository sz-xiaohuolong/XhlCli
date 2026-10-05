package com.xhlcli.skill;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Skill 元数据领域对象，从 SKILL.md 的 YAML Frontmatter 中提取。
 */
public record SkillMetadata(
        String name,
        String description,
        List<String> allowedTools,
        String author,
        List<String> tags) {

    public SkillMetadata {
        name = name != null ? name.trim() : "";
        description = description != null ? description.trim() : "";
        allowedTools = allowedTools != null ? List.copyOf(allowedTools) : List.of();
        author = author != null ? author.trim() : "";
        tags = tags != null ? List.copyOf(tags) : List.of();
    }

    public static SkillMetadata of(String name, String description) {
        return new SkillMetadata(name, description, List.of(), "", List.of());
    }

    public static SkillMetadata of(String name, String description, List<String> allowedTools) {
        return new SkillMetadata(name, description, allowedTools, "", List.of());
    }
}
