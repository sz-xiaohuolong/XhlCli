package com.xhlcli.skill.tool;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhlcli.hitl.ApprovalPolicy;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.ToolOutput;
import com.xhlcli.skill.SkillRegistry;
import com.xhlcli.skill.builtin.BuiltinSkills;
import com.xhlcli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillToolIntegrationTest {

    @Test
    void testBuiltinSkillsRegistrationAndToolInvocation() {
        SkillRegistry skillRegistry = new SkillRegistry(null, null, BuiltinSkills.all());
        skillRegistry.scanAndReload();

        assertEquals(2, skillRegistry.listAll().size());
        assertTrue(skillRegistry.find("git-feature-workflow").isPresent());
        assertTrue(skillRegistry.find("web-research").isPresent());

        LoadSkillTool loadSkillTool = new LoadSkillTool(skillRegistry);
        ToolRegistry toolRegistry = new ToolRegistry(List.of(loadSkillTool));

        assertTrue(toolRegistry.find("load_skill").isPresent());
        assertTrue(ApprovalPolicy.isReadOnly("load_skill"));

        // Invoke load_skill for git-feature-workflow
        ObjectNode args = JsonNodeFactory.instance.objectNode().put("name", "git-feature-workflow");
        ToolOutput output = loadSkillTool.execute(args, new CancellationToken());

        assertTrue(output.summary().contains("git-feature-workflow"));
        assertTrue(output.summary().contains("Conventional Commits"));
        assertEquals("loaded", output.data().get("status").asText());
    }

    @Test
    void testLoadSkillForWebResearchWorkflow() {
        SkillRegistry skillRegistry = new SkillRegistry(null, null, BuiltinSkills.all());
        skillRegistry.scanAndReload();

        LoadSkillTool loadSkillTool = new LoadSkillTool(skillRegistry);
        ObjectNode args = JsonNodeFactory.instance.objectNode().put("name", "web-research");
        ToolOutput output = loadSkillTool.execute(args, new CancellationToken());

        assertTrue(output.summary().contains("web-research"));
        assertTrue(output.summary().contains("web_search"));
        assertTrue(output.summary().contains("web_fetch"));
        assertEquals("loaded", output.data().get("status").asText());
    }
}
