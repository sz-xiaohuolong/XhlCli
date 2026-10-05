package com.xhlcli.skill;

import com.xhlcli.prompt.PromptSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Skill 注册中心，负责多层级 Skill 发现、同名三层覆盖（PROJECT > USER > BUILTIN）、启停管理与热重载。
 */
public final class SkillRegistry {
    private final Path userSkillsDir;
    private final Path projectSkillsDir;
    private final List<SkillDefinition> builtinSkills = new ArrayList<>();
    private final Map<String, SkillDefinition> skills = new ConcurrentHashMap<>();
    private final Map<String, Boolean> userToggles = new ConcurrentHashMap<>();

    public SkillRegistry(Path userSkillsDir, Path projectSkillsDir) {
        this.userSkillsDir = userSkillsDir;
        this.projectSkillsDir = projectSkillsDir;
    }

    public SkillRegistry(Path userSkillsDir, Path projectSkillsDir, List<SkillDefinition> builtinSkills) {
        this.userSkillsDir = userSkillsDir;
        this.projectSkillsDir = projectSkillsDir;
        if (builtinSkills != null) {
            this.builtinSkills.addAll(builtinSkills);
        }
    }

    /**
     * 注册内置 Skill。
     */
    public synchronized void registerBuiltin(SkillDefinition builtinSkill) {
        if (builtinSkill != null) {
            builtinSkills.add(builtinSkill);
            // 若当前未被更高优先级覆盖，更新到 skills
            String name = builtinSkill.name().toLowerCase(Locale.ROOT);
            SkillDefinition existing = skills.get(name);
            if (existing == null || existing.source() == PromptSource.BUILTIN) {
                skills.put(name, builtinSkill);
            }
        }
    }

    /**
     * 执行全量三层扫描并刷新注册表（BUILTIN -> USER -> PROJECT）。
     */
    public synchronized void scanAndReload() {
        Map<String, SkillDefinition> scanned = new HashMap<>();

        // 1. 载入内置 Skills
        for (SkillDefinition builtin : builtinSkills) {
            scanned.put(builtin.name().toLowerCase(Locale.ROOT), builtin);
        }

        // 2. 扫描用户级目录 (~/.xhlcli/skills/)
        if (userSkillsDir != null && Files.isDirectory(userSkillsDir)) {
            scanDirectory(userSkillsDir, PromptSource.USER, scanned);
        }

        // 3. 扫描项目级目录 (.xhlcli/skills/)
        if (projectSkillsDir != null && Files.isDirectory(projectSkillsDir)) {
            scanDirectory(projectSkillsDir, PromptSource.PROJECT, scanned);
        }

        // 4. 应用用户历史显式设置的启停状态
        for (SkillDefinition def : scanned.values()) {
            String name = def.name().toLowerCase(Locale.ROOT);
            Boolean explicitToggle = userToggles.get(name);
            if (explicitToggle != null) {
                def.setEnabled(explicitToggle);
            }
        }

        skills.clear();
        skills.putAll(scanned);
    }

    private void scanDirectory(Path baseDir, PromptSource source, Map<String, SkillDefinition> targetMap) {
        try (Stream<Path> stream = Files.list(baseDir)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                SkillDefinition def = SkillParser.parse(dir, source);
                String name = def.name().toLowerCase(Locale.ROOT);
                SkillDefinition existing = targetMap.get(name);
                // 覆盖规则：更高优先级覆盖低优先级；同优先级后发现覆盖先发现
                if (existing == null || source.priority() >= existing.source().priority()) {
                    targetMap.put(name, def);
                }
            });
        } catch (IOException ignored) {}
    }

    /**
     * 获取按名称升序排列的所有已注册 Skill 列表。
     */
    public List<SkillDefinition> listAll() {
        return skills.values().stream()
                .sorted(Comparator.comparing(SkillDefinition::name))
                .toList();
    }

    /**
     * 查找指定名称的 Skill（忽略大小写）。
     */
    public Optional<SkillDefinition> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(skills.get(name.trim().toLowerCase(Locale.ROOT)));
    }

    /**
     * 启用指定 Skill。
     *
     * @return true 若找到并成功启用；false 若未找到或本身处于错误状态
     */
    public synchronized boolean enable(String name) {
        Optional<SkillDefinition> opt = find(name);
        if (opt.isPresent()) {
            SkillDefinition def = opt.get();
            if (def.isHealthy()) {
                def.setEnabled(true);
                userToggles.put(def.name().toLowerCase(Locale.ROOT), true);
                return true;
            }
        }
        return false;
    }

    /**
     * 禁用指定 Skill。
     *
     * @return true 若找到并成功禁用；false 若未找到
     */
    public synchronized boolean disable(String name) {
        Optional<SkillDefinition> opt = find(name);
        if (opt.isPresent()) {
            SkillDefinition def = opt.get();
            def.setEnabled(false);
            userToggles.put(def.name().toLowerCase(Locale.ROOT), false);
            return true;
        }
        return false;
    }

    /**
     * 生成紧凑的索引提示词。
     */
    public String generateIndexPrompt(int maxBudget) {
        return SkillIndex.generate(skills.values(), maxBudget);
    }
}
