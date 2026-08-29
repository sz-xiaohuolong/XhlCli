package com.xhlcli.tool.local;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.ToolOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class ListDirToolTest {

    @TempDir
    Path tempDir;

    @Test
    public void testListDir() throws Exception {
        Files.createDirectories(tempDir.resolve("dir1"));
        Files.createFile(tempDir.resolve("file1.txt"));
        Files.createDirectories(tempDir.resolve(".git"));
        Files.createFile(tempDir.resolve(".git/config"));

        WorkspacePathResolver resolver = new WorkspacePathResolver(tempDir);
        ListDirTool tool = new ListDirTool(resolver);

        ObjectNode args = JsonNodeFactory.instance.objectNode();
        ToolOutput output = tool.execute(args, new CancellationToken());

        String summary = output.summary();
        assertTrue(summary.contains("[D] dir1"));
        assertTrue(summary.contains("[F] file1.txt"));
        assertFalse(summary.contains(".git"));
    }
}
