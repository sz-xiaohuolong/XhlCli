package com.xhlcli.skill.tool;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.ToolOutput;
import com.xhlcli.prompt.PromptSource;
import com.xhlcli.skill.SkillDefinition;
import com.xhlcli.skill.SkillMetadata;
import com.xhlcli.skill.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LoadSkillToolTest {

    private SkillRegistry registry;
    private LoadSkillTool tool;

    @BeforeEach
    void setUp() {
        SkillDefinition skill1 = SkillDefinition.healthy(
                SkillMetadata.of("git-feature-workflow", "Git workflow instructions"),
                PromptSource.BUILTIN,
                null,
                null,
                "Step 1: Check diff\nStep 2: Commit"
        );
        SkillDefinition skill2 = SkillDefinition.healthy(
                SkillMetadata.of("disabled-skill", "Disabled"),
                PromptSource.BUILTIN,
                null,
                null,
                "Disabled instructions"
        );
        skill2.setEnabled(false);

        registry = new SkillRegistry(null, null, List.of(skill1, skill2));
        registry.scanAndReload();
        tool = new LoadSkillTool(registry);
    }

    @Test
    void testLoadValidSkillSuccessfully() {
        ObjectNode args = JsonNodeFactory.instance.objectNode().put("name", "git-feature-workflow");
        ToolOutput output = tool.execute(args, new CancellationToken());

        assertNotNull(output);
        assertTrue(output.summary().contains("git-feature-workflow"));
        assertTrue(output.summary().contains("Step 1: Check diff"));
        assertEquals("loaded", output.data().get("status").asText());
        assertTrue(tool.getActiveSkillNames().contains("git-feature-workflow"));
    }

    @Test
    void testLoadAlreadyLoadedSkillPreventsDuplication() {
        ObjectNode args = JsonNodeFactory.instance.objectNode().put("name", "git-feature-workflow");
        // First load
        tool.execute(args, new CancellationToken());

        // Second load in same run
        ToolOutput second = tool.execute(args, new CancellationToken());
        assertEquals("already_loaded", second.data().get("status").asText());
        assertTrue(second.summary().contains("already loaded in the current context"));
    }

    @Test
    void testLoadNonExistentSkillReturnsError() {
        ObjectNode args = JsonNodeFactory.instance.objectNode().put("name", "missing-skill");
        ToolOutput output = tool.execute(args, new CancellationToken());

        assertTrue(output.summary().contains("not found"));
        assertEquals("Skill not found.", output.continueHint());
    }

    @Test
    void testLoadDisabledSkillReturnsError() {
        ObjectNode args = JsonNodeFactory.instance.objectNode().put("name", "disabled-skill");
        ToolOutput output = tool.execute(args, new CancellationToken());

        assertTrue(output.summary().contains("currently disabled"));
        assertEquals("Skill is disabled.", output.continueHint());
    }

    @Test
    void testResetRunStateClearsActiveSkills() {
        ObjectNode args = JsonNodeFactory.instance.objectNode().put("name", "git-feature-workflow");
        tool.execute(args, new CancellationToken());
        assertEquals(1, tool.getActiveSkillNames().size());

        tool.resetRunState();
        assertEquals(0, tool.getActiveSkillNames().size());

        // Can load again as fresh load
        ToolOutput reload = tool.execute(args, new CancellationToken());
        assertEquals("loaded", reload.data().get("status").asText());
    }
}
