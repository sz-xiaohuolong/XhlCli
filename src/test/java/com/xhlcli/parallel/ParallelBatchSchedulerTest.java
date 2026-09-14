package com.xhlcli.parallel;

import com.xhlcli.model.ToolCall;
import com.xhlcli.tool.ToolRegistry;
import com.xhlcli.tool.local.ApplyPatchTool;
import com.xhlcli.tool.local.ExecuteCommandTool;
import com.xhlcli.tool.local.ListDirTool;
import com.xhlcli.tool.local.ReadFileTool;
import com.xhlcli.tool.local.WorkspacePathResolver;
import com.xhlcli.tool.local.WriteFileTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ParallelBatchSchedulerTest {

    @TempDir
    Path tempDir;

    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        WorkspacePathResolver resolver = new WorkspacePathResolver(tempDir);
        registry = new ToolRegistry(List.of(
                new ReadFileTool(resolver),
                new WriteFileTool(resolver),
                new ApplyPatchTool(resolver),
                new ListDirTool(resolver),
                new ExecuteCommandTool(resolver)
        ));
    }

    @Test
    void emptyOrNullListReturnsEmptyBatches() {
        assertTrue(ParallelBatchScheduler.schedule(null, registry, 4).isEmpty());
        assertTrue(ParallelBatchScheduler.schedule(List.of(), registry, 4).isEmpty());
    }

    @Test
    void allReadOnlyToolsGroupedIntoSingleParallelBatchWhenUnderLimit() {
        List<ToolCall> calls = List.of(
                new ToolCall("c1", "read_file", "{\"path\":\"a.txt\"}"),
                new ToolCall("c2", "read_file", "{\"path\":\"b.txt\"}"),
                new ToolCall("c3", "list_dir", "{\"path\":\".\"}")
        );

        List<ParallelBatch> batches = ParallelBatchScheduler.schedule(calls, registry, 4);
        assertEquals(1, batches.size());
        assertTrue(batches.get(0).isParallel());
        assertEquals(3, batches.get(0).size());
        assertEquals(0, batches.get(0).calls().get(0).originalIndex());
        assertEquals(1, batches.get(0).calls().get(1).originalIndex());
        assertEquals(2, batches.get(0).calls().get(2).originalIndex());
    }

    @Test
    void splitWhenExceedingMaxConcurrency() {
        List<ToolCall> calls = List.of(
                new ToolCall("c1", "read_file", "{\"path\":\"a.txt\"}"),
                new ToolCall("c2", "read_file", "{\"path\":\"b.txt\"}"),
                new ToolCall("c3", "read_file", "{\"path\":\"c.txt\"}"),
                new ToolCall("c4", "read_file", "{\"path\":\"d.txt\"}")
        );

        List<ParallelBatch> batches = ParallelBatchScheduler.schedule(calls, registry, 2);
        assertEquals(2, batches.size());
        assertTrue(batches.get(0).isParallel());
        assertEquals(2, batches.get(0).size());
        assertTrue(batches.get(1).isParallel());
        assertEquals(2, batches.get(1).size());

        assertEquals(0, batches.get(0).calls().get(0).originalIndex());
        assertEquals(1, batches.get(0).calls().get(1).originalIndex());
        assertEquals(2, batches.get(1).calls().get(0).originalIndex());
        assertEquals(3, batches.get(1).calls().get(1).originalIndex());
    }

    @Test
    void serialToolsSplitParallelBatches() {
        List<ToolCall> calls = List.of(
                new ToolCall("c1", "read_file", "{\"path\":\"a.txt\"}"),
                new ToolCall("c2", "execute_command", "{\"command\":\"npm test\"}"),
                new ToolCall("c3", "read_file", "{\"path\":\"b.txt\"}")
        );

        List<ParallelBatch> batches = ParallelBatchScheduler.schedule(calls, registry, 4);
        assertEquals(3, batches.size());
        assertTrue(batches.get(0).isParallel());
        assertEquals(1, batches.get(0).size());
        assertEquals("read_file", batches.get(0).calls().get(0).call().name());

        assertFalse(batches.get(1).isParallel());
        assertEquals(1, batches.get(1).size());
        assertEquals("execute_command", batches.get(1).calls().get(0).call().name());

        assertTrue(batches.get(2).isParallel());
        assertEquals(1, batches.get(2).size());
        assertEquals("read_file", batches.get(2).calls().get(0).call().name());
    }

    @Test
    void writeToolForcesSequentialBatch() {
        List<ToolCall> calls = List.of(
                new ToolCall("c1", "write_file", "{\"path\":\"a.txt\",\"content\":\"hi\"}"),
                new ToolCall("c2", "write_file", "{\"path\":\"b.txt\",\"content\":\"hello\"}")
        );

        List<ParallelBatch> batches = ParallelBatchScheduler.schedule(calls, registry, 4);
        assertEquals(2, batches.size());
        assertFalse(batches.get(0).isParallel());
        assertFalse(batches.get(1).isParallel());
    }

    @Test
    void sameFileReadCallsDoNotConflictAndStayInSameBatch() {
        List<ToolCall> calls = List.of(
                new ToolCall("c1", "read_file", "{\"path\":\"a.txt\",\"offset\":1}"),
                new ToolCall("c2", "read_file", "{\"path\":\"a.txt\",\"offset\":100}")
        );

        List<ParallelBatch> batches = ParallelBatchScheduler.schedule(calls, registry, 4);
        assertEquals(1, batches.size());
        assertTrue(batches.get(0).isParallel());
        assertEquals(2, batches.get(0).size());
    }
}
