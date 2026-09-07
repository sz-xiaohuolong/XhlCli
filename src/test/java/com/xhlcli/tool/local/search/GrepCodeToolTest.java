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

    @Test
    void testGrepCodeNoMatchProvidesHelpfulSuggestions(@TempDir Path tempDir) throws Exception {
        WorkspacePathResolver resolver = new WorkspacePathResolver(tempDir);
        GrepCodeTool tool = new GrepCodeTool(resolver);
        Files.writeString(tempDir.resolve("target.txt"), "apple\nbanana\ncherry\n");

        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("pattern", "Orange");
        args.put("glob", "**/*.txt");
        args.put("case_sensitive", true);

        ToolOutput out = tool.execute(args, new CancellationToken());
        String summary = out.summary();
        assertTrue(summary.contains("未找到匹配内容: \"Orange\""));
        assertTrue(summary.contains("限定 glob: **/*.txt"));
        assertTrue(summary.contains("建议："));
        assertTrue(summary.contains("case_sensitive"));
    }

    @Test
    void testInvalidRegexReturnsSyntaxError(@TempDir Path tempDir) throws Exception {
        WorkspacePathResolver resolver = new WorkspacePathResolver(tempDir);
        GrepCodeTool tool = new GrepCodeTool(resolver);

        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("pattern", "[unclosed_regex");
        args.put("regex", true);

        ToolOutput out = tool.execute(args, new CancellationToken());
        String summary = out.summary();
        assertTrue(summary.contains("代码搜索失败: 正则表达式无效") || summary.contains("代码搜索失败"));
    }
}
