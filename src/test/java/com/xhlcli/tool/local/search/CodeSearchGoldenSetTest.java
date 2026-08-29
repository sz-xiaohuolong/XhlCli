package com.xhlcli.tool.local.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.ToolOutput;
import com.xhlcli.tool.local.ReadFileTool;
import com.xhlcli.tool.local.WorkspacePathResolver;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeSearchGoldenSetTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_CHARS = 6_000;

    @Test
    void grepThenReadGoldenSetStaysWithinBudgetAndFindsExpectedCode() throws Exception {
        Path projectRoot = Path.of("").toAbsolutePath().normalize();
        WorkspacePathResolver resolver = new WorkspacePathResolver(projectRoot);
        GrepCodeTool grepTool = new GrepCodeTool(resolver);
        ReadFileTool readTool = new ReadFileTool(resolver);
        List<GoldenCase> cases = loadGoldenSet();

        String previous = System.getProperty("xhlcli.search.disable.rg");
        System.setProperty("xhlcli.search.disable.rg", "true");
        try {
            for (GoldenCase goldenCase : cases) {
                Path expectedFile = projectRoot.resolve(goldenCase.expectedPath()).normalize();
                int expectedLine = lineContaining(expectedFile, goldenCase.expectedText());

                ObjectNode grepArgs = MAPPER.createObjectNode();
                grepArgs.put("pattern", goldenCase.pattern());
                grepArgs.put("glob", goldenCase.glob());
                grepArgs.put("max_results", 20);
                grepArgs.put("head_limit", 5);
                grepArgs.put("max_chars", MAX_CHARS);

                ToolOutput grepOutput = grepTool.execute(grepArgs, new CancellationToken());
                String grepResult = grepOutput.summary();

                assertTrue(grepResult.length() <= MAX_CHARS + 500,
                        () -> goldenCase.id() + " exceeded grep output budget: " + grepResult.length());
                assertTrue(grepResult.contains(goldenCase.expectedPath() + ":" + expectedLine),
                        () -> goldenCase.id() + " did not locate expected line. Output:\n" + grepResult);
                assertTrue(grepResult.contains("suggested_reads"),
                        () -> goldenCase.id() + " should guide the Agent to read nearby lines");

                int offset = Math.max(1, expectedLine - 20);
                ObjectNode readArgs = MAPPER.createObjectNode();
                readArgs.put("path", goldenCase.expectedPath());
                readArgs.put("offset", offset);
                readArgs.put("limit", 80);

                ToolOutput readOutput = readTool.execute(readArgs, new CancellationToken());
                String readResult = readOutput.summary();
                assertTrue(readResult.contains(goldenCase.expectedText()),
                        () -> goldenCase.id() + " did not read expected context. Output:\n" + readResult);
            }
        } finally {
            restoreSystemProperty("xhlcli.search.disable.rg", previous);
        }
    }

    private List<GoldenCase> loadGoldenSet() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/code-search/golden-set.json")) {
            assertNotNull(in, "golden-set.json should be packaged as a test resource");
            List<GoldenCase> cases = MAPPER.readValue(in, new TypeReference<>() {});
            assertFalse(cases.isEmpty(), "golden set should contain at least one case");
            return cases;
        }
    }

    private int lineContaining(Path file, String text) throws Exception {
        List<String> lines = Files.readAllLines(file);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(text)) {
                return i + 1;
            }
        }
        throw new AssertionError("Expected text not found in " + file + ": " + text);
    }

    private static void restoreSystemProperty(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }

    private record GoldenCase(
            String id,
            String question,
            String pattern,
            String glob,
            String expectedPath,
            String expectedText
    ) {}
}
