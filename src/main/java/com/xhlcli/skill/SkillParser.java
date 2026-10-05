package com.xhlcli.skill;

import com.xhlcli.prompt.PromptSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * SKILL.md 解析器，负责提取 YAML Frontmatter 元数据与 Markdown 指导正文。
 * 具备健壮的容错隔离能力，单 Skill 语法异常不会抛出未检异常阻断全局。
 */
public final class SkillParser {
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9-_]{1,64}$");

    private SkillParser() {}

    /**
     * 解析指定目录下的 SKILL.md。
     */
    public static SkillDefinition parse(Path skillDir, PromptSource source) {
        Objects.requireNonNull(skillDir, "skillDir");
        Objects.requireNonNull(source, "source");

        String dirName = skillDir.getFileName() != null ? skillDir.getFileName().toString() : "unknown";
        Path skillMdPath = skillDir.resolve("SKILL.md");
        if (!Files.exists(skillMdPath)) {
            skillMdPath = skillDir.resolve("skill.md");
        }
        if (!Files.exists(skillMdPath)) {
            return SkillDefinition.error(dirName, source, skillDir, null, "SKILL.md not found in " + skillDir);
        }

        try {
            String content = Files.readString(skillMdPath, StandardCharsets.UTF_8);
            return parseContent(content, skillDir, skillMdPath, source);
        } catch (IOException e) {
            return SkillDefinition.error(dirName, source, skillDir, skillMdPath, "Failed to read SKILL.md: " + e.getMessage());
        }
    }

    /**
     * 解析 SKILL.md 文本内容。
     */
    public static SkillDefinition parseContent(
            String content,
            Path skillDir,
            Path skillMdPath,
            PromptSource source) {
        String dirName = skillDir != null && skillDir.getFileName() != null
                ? skillDir.getFileName().toString() : "unknown";

        if (content == null || content.isBlank()) {
            return SkillDefinition.error(dirName, source, skillDir, skillMdPath, "SKILL.md is empty");
        }

        String trimmed = content.trim();
        if (!trimmed.startsWith("---")) {
            return SkillDefinition.error(dirName, source, skillDir, skillMdPath, "Missing YAML frontmatter (must start with ---)");
        }

        int secondDashIndex = trimmed.indexOf("---", 3);
        if (secondDashIndex == -1) {
            return SkillDefinition.error(dirName, source, skillDir, skillMdPath, "Unclosed YAML frontmatter: missing closing ---");
        }

        String frontmatter = trimmed.substring(3, secondDashIndex).trim();
        String instructions = trimmed.substring(secondDashIndex + 3).trim();

        Map<String, Object> yamlMap = parseSimpleYaml(frontmatter);

        String name = getString(yamlMap, "name");
        if (name == null || name.isBlank()) {
            // 回退使用目录名
            name = dirName.toLowerCase(Locale.ROOT);
        }

        if (!NAME_PATTERN.matcher(name).matches()) {
            return SkillDefinition.error(name, source, skillDir, skillMdPath,
                    "Invalid skill name '" + name + "': must match regex ^[a-z0-9-_]{1,64}$");
        }

        String description = getString(yamlMap, "description");
        if (description == null || description.isBlank()) {
            return SkillDefinition.error(name, source, skillDir, skillMdPath, "Missing 'description' in SKILL.md frontmatter");
        }

        List<String> allowedTools = getList(yamlMap, "allowed-tools", "allowed_tools");
        String author = getString(yamlMap, "author");
        List<String> tags = getList(yamlMap, "tags");

        SkillMetadata metadata = new SkillMetadata(name, description, allowedTools, author, tags);
        return SkillDefinition.healthy(metadata, source, skillDir, skillMdPath, instructions);
    }

    private static Map<String, Object> parseSimpleYaml(String yamlText) {
        Map<String, Object> map = new LinkedHashMap<>();
        String[] lines = yamlText.split("\r?\n");
        String currentKey = null;
        List<String> currentList = null;

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            if (line.startsWith("- ") && currentList != null) {
                currentList.add(line.substring(2).trim().replaceAll("^\"|\"$|^'|'$", ""));
                continue;
            }

            int colonIdx = line.indexOf(':');
            if (colonIdx > 0) {
                String key = line.substring(0, colonIdx).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(colonIdx + 1).trim();

                if (value.isEmpty()) {
                    currentKey = key;
                    currentList = new ArrayList<>();
                    map.put(key, currentList);
                } else {
                    currentKey = null;
                    currentList = null;
                    // 处理带逗号的列表或普通文本
                    value = value.replaceAll("^\"|\"$|^'|'$", "");
                    map.put(key, value);
                }
            }
        }
        return map;
    }

    private static String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof String s) {
            return s.trim();
        }
        return null;
    }

    private static List<String> getList(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object val = map.get(key);
            if (val instanceof List<?> l) {
                List<String> list = new ArrayList<>();
                for (Object item : l) {
                    if (item != null) {
                        list.add(item.toString().trim());
                    }
                }
                return list;
            } else if (val instanceof String s && !s.isBlank()) {
                String[] parts = s.split(",");
                List<String> list = new ArrayList<>();
                for (String part : parts) {
                    if (!part.trim().isEmpty()) {
                        list.add(part.trim());
                    }
                }
                return list;
            }
        }
        return List.of();
    }
}
