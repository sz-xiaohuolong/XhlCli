package com.xhlcli.tool.local;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.ToolOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplyPatchToolTest {

    @TempDir
    Path tempDir;

    private ApplyPatchTool tool;

    @BeforeEach
    void setUp() {
        tool = new ApplyPatchTool(new WorkspacePathResolver(tempDir));
    }

    @Test
    void testUniqueMatchReplacementSucceeds() throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "hello world");

        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("path", "test.txt");
        args.put("old_text", "world");
        args.put("new_text", "java");

        ToolOutput output = tool.execute(args, new CancellationToken());

        assertEquals("替换成功", output.summary());
        assertEquals("hello java", Files.readString(file));
    }

    @Test
    void testZeroMatchesReturnsError() throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "hello world");

        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("path", "test.txt");
        args.put("old_text", "python");
        args.put("new_text", "java");

        ToolOutput output = tool.execute(args, new CancellationToken());

        assertEquals("未找到匹配的待替换文本", output.summary());
    }

    @Test
    void testMultipleMatchesReturnsError() throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "hello world, hello universe");

        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("path", "test.txt");
        args.put("old_text", "hello");
        args.put("new_text", "hi");

        ToolOutput output = tool.execute(args, new CancellationToken());

        assertTrue(output.summary().contains("匹配到 2 处待替换文本"));
    }

    @Test
    void testFileNotFoundError() throws Exception {
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("path", "nonexistent.txt");
        args.put("old_text", "python");
        args.put("new_text", "java");

        ToolOutput output = tool.execute(args, new CancellationToken());

        assertTrue(output.summary().contains("文件不存在或不是普通文件"));
    }
}
