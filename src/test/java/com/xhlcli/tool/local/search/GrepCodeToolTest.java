package com.xhlcli.tool.local.search;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.ToolOutput;
import com.xhlcli.tool.local.WorkspacePathResolver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GrepCodeToolTest {
    @BeforeAll
    static void setup() {
        System.setProperty("xhlcli.search.disable.rg", "true");
    }

    @AfterAll
    static void teardown() {
        System.clearProperty("xhlcli.search.disable.rg");
    }

    @Test
    void testGrepCode(@TempDir Path tempDir) throws Exception {
        WorkspacePathResolver resolver = new WorkspacePathResolver(tempDir);
        GrepCodeTool tool = new GrepCodeTool(resolver);
        
        Files.writeString(tempDir.resolve("target.txt"), "apple\nbanana\ncherry\n");

        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("pattern", "banana");
        
        ToolOutput out = tool.execute(args, new CancellationToken());
        String summary = out.summary();
        assertTrue(summary.contains("target.txt:2"));
        assertTrue(summary.contains(">    2 | banana"));
        assertTrue(summary.contains("suggested_reads"));
    }
}
