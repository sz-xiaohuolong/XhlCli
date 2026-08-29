package com.xhlcli.tool.local;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.ToolOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecuteCommandToolTest {

    @TempDir
    Path tempDir;

    private ExecuteCommandTool tool;

    @BeforeEach
    void setUp() {
        tool = new ExecuteCommandTool(new WorkspacePathResolver(tempDir));
    }

    @Test
    void testSimpleCommandExecution() throws Exception {
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("command", "echo hello");

        ToolOutput output = tool.execute(args, new CancellationToken());

        assertTrue(output.summary().contains("exit code: 0"));
        assertTrue(output.summary().contains("hello"));
    }

    @Test
    void testWorkingDirectoryIsProjectRoot() throws Exception {
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("command", "pwd");

        ToolOutput output = tool.execute(args, new CancellationToken());

        assertTrue(output.summary().contains(tempDir.toRealPath().toString()) || output.summary().contains(tempDir.toAbsolutePath().toString()));
    }

    @Test
    void testTimeoutEnforcement() throws Exception {
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("command", "sleep 10");
        args.put("timeout_seconds", 1);

        ToolOutput output = tool.execute(args, new CancellationToken());

        assertTrue(output.summary().contains("命令执行超时"));
    }

    @Test
    void testOutputTruncation() throws Exception {
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("command", "for i in {1..2000}; do echo -n 'aaaaa'; done");

        ToolOutput output = tool.execute(args, new CancellationToken());

        assertTrue(output.summary().contains("...(输出已截断)"));
        assertTrue(output.summary().length() <= 8100);
    }

    @Test
    void testCancellation() throws Exception {
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("command", "sleep 10");

        CancellationToken token = new CancellationToken();
        
        Thread cancelThread = new Thread(() -> {
            try { Thread.sleep(200); } catch (Exception e) {}
            token.cancel();
        });
        cancelThread.start();

        ToolOutput output = tool.execute(args, token);

        assertTrue(output.summary().contains("命令已被取消"));
    }
}
