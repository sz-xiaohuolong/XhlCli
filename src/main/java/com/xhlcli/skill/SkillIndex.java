package com.xhlcli.skill;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * 紧凑 Skill 索引生成器，用于向系统提示词（Layer 6）注入可用技能摘要。
 * 遵循渐进式披露原则，仅输出名称与描述，严格控制字符上限防 Prompt 膨胀。
 */
public final class SkillIndex {
    public static final int DEFAULT_MAX_INDEX_CHARS = 2500;

    private SkillIndex() {}

    /**
     * 生成供注入 System Prompt 的紧凑 Skill 索引文本。
     *
     * @param skills   所有候选 Skill 集合
     * @param maxChars 字符预算上限
     * @return 紧凑索引 Markdown
     */
    public static String generate(Collection<SkillDefinition> skills, int maxChars) {
        if (skills == null || skills.isEmpty()) {
            return "";
        }

        List<SkillDefinition> enabledSkills = skills.stream()
                .filter(SkillDefinition::isEnabled)
                .sorted(Comparator.comparing(SkillDefinition::name))
                .toList();

        if (enabledSkills.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("When a task matches a skill's description, call `load_skill(name)` to retrieve detailed instructions.\n");

        int count = 0;
        int omitted = 0;

        for (SkillDefinition skill : enabledSkills) {
            String line = formatSkillSummary(skill);
            if (sb.length() + line.length() + 50 > maxChars) {
                omitted++;
            } else {
                sb.append(line).append("\n");
                count++;
            }
        }

        if (omitted > 0) {
            sb.append("... [").append(omitted).append(" more skills available, use `/skill list` to view all]\n");
        }

        return sb.toString().trim();
    }

    private static String formatSkillSummary(SkillDefinition skill) {
        String desc = skill.description();
        // 去除多余换行
        desc = desc.replaceAll("\\s+", " ").trim();
        if (desc.length() > 150) {
            desc = desc.substring(0, 147) + "...";
        }
        return "- `" + skill.name() + "`: " + desc;
    }
}
