package com.xhlcli.skill;

import com.xhlcli.prompt.PromptSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillRegistryTest {

    @Test
    void testThreeTierScanningAndOverriding(@TempDir Path tempDir) throws IOException {
        Path userSkillsDir = tempDir.resolve("user-skills");
        Path projectSkillsDir = tempDir.resolve("project-skills");
        Files.createDirectories(userSkillsDir);
        Files.createDirectories(projectSkillsDir);

        // 1. Built-in skill: "workflow"
        SkillDefinition builtinWorkflow = SkillDefinition.healthy(
                SkillMetadata.of("workflow", "Builtin workflow description"),
                PromptSource.BUILTIN,
                null,
                null,
                "Builtin instructions"
        );

        // 2. User skill: "workflow" (should override builtin) and "user-only"
        Path userWorkflow = userSkillsDir.resolve("workflow");
        Files.createDirectories(userWorkflow);
        Files.writeString(userWorkflow.resolve("SKILL.md"), """
                ---
                name: workflow
                description: User workflow description
                ---
                User instructions
                """);

        Path userOnly = userSkillsDir.resolve("user-only");
        Files.createDirectories(userOnly);
        Files.writeString(userOnly.resolve("SKILL.md"), """
                ---
                name: user-only
                description: User only skill
                ---
                User only instructions
                """);

        // 3. Project skill: "workflow" (should override user workflow)
        Path projectWorkflow = projectSkillsDir.resolve("workflow");
        Files.createDirectories(projectWorkflow);
        Files.writeString(projectWorkflow.resolve("SKILL.md"), """
                ---
                name: workflow
                description: Project workflow description
                ---
                Project instructions
                """);

        SkillRegistry registry = new SkillRegistry(userSkillsDir, projectSkillsDir, List.of(builtinWorkflow));
        registry.scanAndReload();

        List<SkillDefinition> all = registry.listAll();
        assertEquals(2, all.size());

        SkillDefinition resolvedWorkflow = registry.find("workflow").orElseThrow();
        assertEquals(PromptSource.PROJECT, resolvedWorkflow.source());
        assertEquals("Project workflow description", resolvedWorkflow.description());

        SkillDefinition resolvedUserOnly = registry.find("user-only").orElseThrow();
        assertEquals(PromptSource.USER, resolvedUserOnly.source());
    }

    @Test
    void testEnableDisableToggleAndPersistenceAcrossReload(@TempDir Path tempDir) throws IOException {
        Path projectDir = tempDir.resolve("project-skills");
        Files.createDirectories(projectDir);

        Path skillA = projectDir.resolve("skill-a");
        Files.createDirectories(skillA);
        Files.writeString(skillA.resolve("SKILL.md"), """
                ---
                name: skill-a
                description: Skill A
                ---
                Body A
                """);

        SkillRegistry registry = new SkillRegistry(null, projectDir);
        registry.scanAndReload();

        SkillDefinition defA = registry.find("skill-a").orElseThrow();
        assertTrue(defA.isEnabled());

        // Disable it
        assertTrue(registry.disable("skill-a"));
        assertFalse(defA.isEnabled());

        // Reload scanner: disabled state should persist!
        registry.scanAndReload();
        SkillDefinition reloadedA = registry.find("skill-a").orElseThrow();
        assertFalse(reloadedA.isEnabled());

        // Re-enable it
        assertTrue(registry.enable("skill-a"));
        assertTrue(reloadedA.isEnabled());
    }

    @Test
    void testInvalidSkillErrorIsolationDoesNotBlockOtherSkills(@TempDir Path tempDir) throws IOException {
        Path projectDir = tempDir.resolve("project-skills");
        Files.createDirectories(projectDir);

        // Valid skill
        Path validDir = projectDir.resolve("good-skill");
        Files.createDirectories(validDir);
        Files.writeString(validDir.resolve("SKILL.md"), """
                ---
                name: good-skill
                description: A good skill
                ---
                Body
                """);

        // Broken skill: no frontmatter
        Path brokenDir = projectDir.resolve("broken-skill");
        Files.createDirectories(brokenDir);
        Files.writeString(brokenDir.resolve("SKILL.md"), "Broken content without YAML frontmatter");

        SkillRegistry registry = new SkillRegistry(null, projectDir);
        registry.scanAndReload();

        assertEquals(2, registry.listAll().size());

        SkillDefinition good = registry.find("good-skill").orElseThrow();
        assertTrue(good.isHealthy());
        assertTrue(good.isEnabled());

        SkillDefinition broken = registry.find("broken-skill").orElseThrow();
        assertFalse(broken.isHealthy());
        assertFalse(broken.isEnabled());
        assertNotNull(broken.parseError());
    }

    @Test
    void testIndexPromptGenerationOnlyIncludesEnabledHealthySkills() {
        SkillDefinition skill1 = SkillDefinition.healthy(
                SkillMetadata.of("skill-1", "Description for skill 1"),
                PromptSource.BUILTIN, null, null, "Instructions 1"
        );
        SkillDefinition skill2 = SkillDefinition.healthy(
                SkillMetadata.of("skill-2", "Description for skill 2"),
                PromptSource.BUILTIN, null, null, "Instructions 2"
        );
        skill2.setEnabled(false); // disabled

        SkillDefinition skill3 = SkillDefinition.error(
                "skill-3", PromptSource.BUILTIN, null, null, "Parse error"
        );

        SkillRegistry registry = new SkillRegistry(null, null, List.of(skill1, skill2, skill3));
        registry.scanAndReload();

        String prompt = registry.generateIndexPrompt(2000);
        assertTrue(prompt.contains("skill-1"));
        assertFalse(prompt.contains("skill-2")); // disabled, must not appear in prompt
        assertFalse(prompt.contains("skill-3")); // error, must not appear in prompt
    }
}
