package com.xhlcli.skill;

import com.xhlcli.prompt.PromptSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillReferenceResolverTest {

    @Test
    void testReadValidReferenceFile(@TempDir Path tempDir) throws IOException {
        Path skillDir = tempDir.resolve("my-skill");
        Path refDir = skillDir.resolve("references");
        Files.createDirectories(refDir);
        Files.writeString(refDir.resolve("rules.md"), "# Specialized Domain Rules\nRule 1: Always verify.");

        SkillDefinition skill = SkillDefinition.healthy(
                SkillMetadata.of("my-skill", "description"),
                PromptSource.PROJECT,
                skillDir,
                skillDir.resolve("SKILL.md"),
                "instructions"
        );

        String content = SkillReferenceResolver.readReference(skill, "references/rules.md");
        assertTrue(content.contains("# Specialized Domain Rules"));
    }

    @Test
    void testRejectsPathTraversalAttackingOutsideSkillDir(@TempDir Path tempDir) throws IOException {
        Path skillDir = tempDir.resolve("my-skill");
        Files.createDirectories(skillDir);
        Path secretFile = tempDir.resolve("sensitive.txt");
        Files.writeString(secretFile, "TOP_SECRET_CREDENTIALS");

        SkillDefinition skill = SkillDefinition.healthy(
                SkillMetadata.of("my-skill", "description"),
                PromptSource.PROJECT,
                skillDir,
                skillDir.resolve("SKILL.md"),
                "instructions"
        );

        // Attempts to read outside ../sensitive.txt
        SecurityException ex1 = assertThrows(SecurityException.class, () ->
                SkillReferenceResolver.readReference(skill, "../sensitive.txt")
        );
        assertTrue(ex1.getMessage().contains("escapes skill directory"));

        // Attempts to read via subfolder traversal references/../../sensitive.txt
        SecurityException ex2 = assertThrows(SecurityException.class, () ->
                SkillReferenceResolver.readReference(skill, "references/../../sensitive.txt")
        );
        assertTrue(ex2.getMessage().contains("escapes skill directory"));
    }

    @Test
    void testMissingReferenceThrowsIOException(@TempDir Path tempDir) {
        Path skillDir = tempDir.resolve("my-skill");
        SkillDefinition skill = SkillDefinition.healthy(
                SkillMetadata.of("my-skill", "description"),
                PromptSource.PROJECT,
                skillDir,
                skillDir.resolve("SKILL.md"),
                "instructions"
        );

        assertThrows(IOException.class, () ->
                SkillReferenceResolver.readReference(skill, "references/non-existent.md")
        );
    }

    @Test
    void testListSubdirectoryFiles(@TempDir Path tempDir) throws IOException {
        Path skillDir = tempDir.resolve("my-skill");
        Path refDir = skillDir.resolve("references");
        Path scriptDir = skillDir.resolve("scripts");
        Files.createDirectories(refDir);
        Files.createDirectories(scriptDir);

        Files.writeString(refDir.resolve("doc1.md"), "doc 1");
        Files.writeString(refDir.resolve("doc2.md"), "doc 2");
        Files.writeString(scriptDir.resolve("deploy.sh"), "#!/bin/bash");

        SkillDefinition skill = SkillDefinition.healthy(
                SkillMetadata.of("my-skill", "description"),
                PromptSource.PROJECT,
                skillDir,
                skillDir.resolve("SKILL.md"),
                "instructions"
        );

        List<String> list = SkillReferenceResolver.listSubdirectoryFiles(skill, "references");
        assertEquals(2, list.size());
        assertTrue(list.contains("references/doc1.md"));
        assertTrue(list.contains("references/doc2.md"));
    }
}
