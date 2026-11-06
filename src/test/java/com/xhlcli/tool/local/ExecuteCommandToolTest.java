package com.xhlcli.tool.local;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.ToolOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.*;

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
    @Test
    void cancellationWaitsForDescendantsToStop() throws Exception {
        ObjectNode args=JsonNodeFactory.instance.objectNode().put("command", "sh -c 'echo $$ > child.pid; sleep 30; echo survived > survived.txt' & wait");
        var token=new CancellationToken();
        try(var executor=Executors.newSingleThreadExecutor()) {
            var future=executor.submit(()->tool.execute(args,token));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(!Files.exists(tempDir.resolve("child.pid"))&&System.nanoTime()<deadline)Thread.sleep(10);
            assertTrue(Files.exists(tempDir.resolve("child.pid")));
            long pid=Long.parseLong(Files.readString(tempDir.resolve("child.pid")).strip());
            try {
                token.cancel();assertTrue(future.get(6,TimeUnit.SECONDS).summary().contains("取消"));
                org.junit.jupiter.api.Assertions.assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),"取消确认前必须结束子进程");
                org.junit.jupiter.api.Assertions.assertFalse(Files.exists(tempDir.resolve("survived.txt")));
            } finally {ProcessHandle.of(pid).ifPresent(handle->{handle.descendants().forEach(ProcessHandle::destroyForcibly);handle.destroyForcibly();});}
        }
    }
}
