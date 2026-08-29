package com.xhlcli.tool.local;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.ToolOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitDiffToolTest {

    @TempDir
    Path tempDir;

    private GitDiffTool tool;

    @BeforeEach
    void setUp() {
        tool = new GitDiffTool(new WorkspacePathResolver(tempDir));
    }

    @Test
    void testEmptyDiffMessage() throws Exception {
        Process p = new ProcessBuilder("git", "init")
                .directory(tempDir.toFile())
                .start();
        p.waitFor(5, TimeUnit.SECONDS);

        ObjectNode args = JsonNodeFactory.instance.objectNode();
        ToolOutput output = tool.execute(args, new CancellationToken());

        assertEquals("工作区没有未暂存的变更", output.summary());
    }

    @Test
    void testInNonGitDirectoryReturnsFriendlyError() throws Exception {
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        ToolOutput output = tool.execute(args, new CancellationToken());

        assertTrue(output.summary().contains("git diff 失败") || output.summary().contains("执行 git 命令失败"));
    }
}
