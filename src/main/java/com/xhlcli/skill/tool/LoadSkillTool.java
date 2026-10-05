package com.xhlcli.skill.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.ToolDefinition;
import com.xhlcli.model.ToolMetadata;
import com.xhlcli.model.ToolOutput;
import com.xhlcli.skill.SkillDefinition;
import com.xhlcli.skill.SkillRegistry;
import com.xhlcli.tool.Tool;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * load_skill 本地工具：支持 Agent 根据任务匹配动态加载指定 Skill 的详细指导正文。
 * 遵循渐进式披露原则，同一 Run 内部自动去重，避免重复消耗上下文。
 */
public final class LoadSkillTool implements Tool {
    private static final String TOOL_NAME = "load_skill";
    private final SkillRegistry skillRegistry;
    private final Set<String> activeSkillNames = ConcurrentHashMap.newKeySet();
    private final Map<String, String> activeSkillBodies = new ConcurrentHashMap<>();

    public LoadSkillTool(SkillRegistry skillRegistry) {
        this.skillRegistry = Objects.requireNonNull(skillRegistry, "skillRegistry");
    }

    @Override
    public ToolDefinition definition() {
        ObjectNode properties = JsonNodeFactory.instance.objectNode();
        properties.set("name", JsonNodeFactory.instance.objectNode()
                .put("type", "string")
                .put("description", "The name of the skill to load (e.g. 'git-feature-workflow' or 'web-research')."));

        ObjectNode schema = JsonNodeFactory.instance.objectNode().put("type", "object");
        schema.set("properties", properties);
        schema.set("required", JsonNodeFactory.instance.arrayNode().add("name"));
        schema.put("additionalProperties", false);

        return new ToolDefinition(
                TOOL_NAME,
                "Loads detailed workflow instructions and guidelines for a specific skill by name into the context.",
                schema,
                new ToolMetadata(ToolMetadata.RiskLevel.LOW, true, true, false, "Load skill instructions into context.")
        );
    }

    @Override
    public ToolOutput execute(JsonNode arguments, CancellationToken cancellationToken) {
        String skillName = arguments.path("name").asText("").trim();
        ObjectNode data = JsonNodeFactory.instance.objectNode().put("name", skillName);

        if (skillName.isEmpty()) {
            return new ToolOutput("Error: 'name' parameter is required for load_skill.", data, "Missing skill name.");
        }

        Optional<SkillDefinition> opt = skillRegistry.find(skillName);
        if (opt.isEmpty()) {
            return new ToolOutput("Skill '" + skillName + "' not found. Please check available skills in the skill index.", data, "Skill not found.");
        }

        SkillDefinition skill = opt.get();
        if (!skill.isHealthy()) {
            return new ToolOutput("Skill '" + skill.name() + "' cannot be loaded due to configuration errors: " + skill.parseError(), data, "Skill has errors.");
        }
        if (!skill.isEnabled()) {
            return new ToolOutput("Skill '" + skill.name() + "' is currently disabled.", data, "Skill is disabled.");
        }

        String lowerName = skill.name().toLowerCase(Locale.ROOT);
        if (activeSkillNames.contains(lowerName)) {
            data.put("status", "already_loaded");
            return new ToolOutput(
                    "Skill '" + skill.name() + "' is already loaded in the current context. Please follow the instructions already provided.",
                    data,
                    ""
            );
        }

        activeSkillNames.add(lowerName);
        activeSkillBodies.put(lowerName, skill.instructions());
        data.put("status", "loaded");
        data.put("source", skill.source().name());

        String message = String.format(
                "Skill '%s' loaded successfully (%s).\n\n%s",
                skill.name(),
                skill.source().description(),
                skill.instructions()
        );

        return new ToolOutput(message, data, "");
    }

    /**
     * 重置当前 Run 的激活状态（用于多轮对话或新 Run 调度）。
     */
    public void resetRunState() {
        activeSkillNames.clear();
        activeSkillBodies.clear();
    }

    public Set<String> getActiveSkillNames() {
        return Collections.unmodifiableSet(activeSkillNames);
    }

    public Map<String, String> getActiveSkillBodies() {
        return Collections.unmodifiableMap(activeSkillBodies);
    }
}
