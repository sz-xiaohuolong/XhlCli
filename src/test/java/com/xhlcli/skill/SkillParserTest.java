package com.xhlcli.skill;

import com.xhlcli.prompt.PromptSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillParserTest {

    @Test
    void testParseValidSkillContent() {
        String content = """
                ---
                name: code-review
                description: Performs comprehensive code review according to clean code rules.
                allowed-tools: read_file, git_diff
                author: Alice
                tags: review, java
                ---
                # Code Review Instructions
                1. Inspect git diff.
                2. Check naming conventions.
                """;

        SkillDefinition skill = SkillParser.parseContent(
                content,
                Path.of("/dummy/code-review"),
                Path.of("/dummy/code-review/SKILL.md"),
                PromptSource.PROJECT
        );

        assertTrue(skill.isHealthy());
        assertTrue(skill.isEnabled());
        assertNull(skill.parseError());
        assertEquals("code-review", skill.name());
        assertEquals("Performs comprehensive code review according to clean code rules.", skill.description());
        assertEquals(PromptSource.PROJECT, skill.source());
        assertEquals(List.of("read_file", "git_diff"), skill.metadata().allowedTools());
        assertEquals("Alice", skill.metadata().author());
        assertEquals(List.of("review", "java"), skill.metadata().tags());
        assertTrue(skill.instructions().contains("# Code Review Instructions"));
    }

    @Test
    void testParseWithYamlListAllowedTools() {
        String content = """
                ---
                name: test-skill
                description: A test skill with yaml list
                allowed-tools:
                  - read_file
                  - execute_command
                ---
                Body content
                """;

        SkillDefinition skill = SkillParser.parseContent(
                content,
                Path.of("/dummy/test-skill"),
                Path.of("/dummy/test-skill/SKILL.md"),
                PromptSource.USER
        );

        assertTrue(skill.isHealthy());
        assertEquals(List.of("read_file", "execute_command"), skill.metadata().allowedTools());
    }

    @Test
    void testFallbackNameToDirectoryNameWhenMissing() {
        String content = """
                ---
                description: No name specified here
                ---
                Some instructions
                """;

        SkillDefinition skill = SkillParser.parseContent(
                content,
                Path.of("/dummy/my-workflow"),
                Path.of("/dummy/my-workflow/SKILL.md"),
                PromptSource.BUILTIN
        );

        assertTrue(skill.isHealthy());
        assertEquals("my-workflow", skill.name());
    }

    @Test
    void testInvalidNameFailsGracefullyWithoutThrowing() {
        String content = """
                ---
                name: Invalid Name With Spaces!
                description: Description here
                ---
                Body
                """;

        SkillDefinition skill = SkillParser.parseContent(
                content,
                Path.of("/dummy/invalid"),
                Path.of("/dummy/invalid/SKILL.md"),
                PromptSource.PROJECT
        );

        assertFalse(skill.isHealthy());
        assertFalse(skill.isEnabled());
        assertNotNull(skill.parseError());
        assertTrue(skill.parseError().contains("Invalid skill name"));
    }

    @Test
    void testMissingDescriptionFailsGracefully() {
        String content = """
                ---
                name: missing-desc
                ---
                Body
                """;

        SkillDefinition skill = SkillParser.parseContent(
                content,
                Path.of("/dummy/missing-desc"),
                Path.of("/dummy/missing-desc/SKILL.md"),
                PromptSource.PROJECT
        );

        assertFalse(skill.isHealthy());
        assertNotNull(skill.parseError());
        assertTrue(skill.parseError().contains("Missing 'description'"));
    }

    @Test
    void testMissingFrontmatterFailsGracefully() {
        String content = "# No Frontmatter at all\nJust markdown body";

        SkillDefinition skill = SkillParser.parseContent(
                content,
                Path.of("/dummy/no-fm"),
                Path.of("/dummy/no-fm/SKILL.md"),
                PromptSource.PROJECT
        );

        assertFalse(skill.isHealthy());
        assertTrue(skill.parseError().contains("Missing YAML frontmatter"));
    }

    @Test
    void testParseFromActualFile(@TempDir Path tempDir) throws IOException {
        Path skillDir = tempDir.resolve("actual-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: actual-skill
                description: Loaded from real file system
                ---
                Real body instructions
                """);

        SkillDefinition skill = SkillParser.parse(skillDir, PromptSource.USER);
        assertTrue(skill.isHealthy());
        assertEquals("actual-skill", skill.name());
        assertEquals(skillDir, skill.directoryPath());
    }
}
