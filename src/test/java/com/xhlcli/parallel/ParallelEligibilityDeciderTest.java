package com.xhlcli.parallel;

import com.xhlcli.model.ToolCall;
import com.xhlcli.tool.ToolRegistry;
import com.xhlcli.tool.demo.CurrentTimeTool;
import com.xhlcli.tool.demo.EchoTool;
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
import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelEligibilityDeciderTest {

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
                new ExecuteCommandTool(resolver),
                new EchoTool(),
                new CurrentTimeTool(Clock.systemUTC())
        ));
    }

    @Test
    void readOnlyAndAllowsParallelToolsAreEligible() {
        ToolCall readCall = new ToolCall("c1", "read_file", "{\"path\":\"a.txt\"}");
        ToolCall listCall = new ToolCall("c2", "list_dir", "{\"path\":\".\"}");

        assertTrue(ParallelEligibilityDecider.isEligible(readCall, registry));
        assertTrue(ParallelEligibilityDecider.isEligible(listCall, registry));
    }

    @Test
    void readOnlyWithoutAllowsParallelFlagIsNotEligible() {
        ToolCall echoCall = new ToolCall("c3", "echo_text", "{\"text\":\"hi\"}");
        ToolCall timeCall = new ToolCall("c4", "current_time", "{}");

        assertFalse(ParallelEligibilityDecider.isEligible(echoCall, registry));
        assertFalse(ParallelEligibilityDecider.isEligible(timeCall, registry));
    }

    @Test
    void writeAndCommandToolsAreNotEligible() {
        ToolCall writeCall = new ToolCall("c1", "write_file", "{\"path\":\"a.txt\",\"content\":\"hello\"}");
        ToolCall patchCall = new ToolCall("c2", "apply_patch", "{\"path\":\"a.txt\",\"patch\":\"...\"}");
        ToolCall cmdCall = new ToolCall("c3", "execute_command", "{\"command\":\"ls\"}");

        assertFalse(ParallelEligibilityDecider.isEligible(writeCall, registry));
        assertFalse(ParallelEligibilityDecider.isEligible(patchCall, registry));
        assertFalse(ParallelEligibilityDecider.isEligible(cmdCall, registry));
    }

    @Test
    void unknownToolIsNotEligible() {
        ToolCall unknownCall = new ToolCall("c1", "unknown_tool", "{}");
        assertFalse(ParallelEligibilityDecider.isEligible(unknownCall, registry));
    }

    @Test
    void readSameFileHasNoConflict() {
        ToolCall read1 = new ToolCall("c1", "read_file", "{\"path\":\"src/Main.java\"}");
        ToolCall read2 = new ToolCall("c2", "read_file", "{\"path\":\"./src/Main.java\"}");

        assertFalse(ParallelEligibilityDecider.hasConflict(read1, read2, registry), "同时读取同一个文件应当无冲突");
    }

    @Test
    void readAndWriteSameFileHasConflict() {
        ToolCall readCall = new ToolCall("c1", "read_file", "{\"path\":\"src/Main.java\"}");
        ToolCall writeCall = new ToolCall("c2", "write_file", "{\"path\":\"src/Main.java\",\"content\":\"new\"}");

        assertTrue(ParallelEligibilityDecider.hasConflict(readCall, writeCall, registry), "读写同一文件应当冲突");
        assertTrue(ParallelEligibilityDecider.hasConflict(writeCall, readCall, registry), "对称读写同一文件应当冲突");
    }

    @Test
    void writeSameFileHasConflict() {
        ToolCall write1 = new ToolCall("c1", "write_file", "{\"path\":\"pom.xml\",\"content\":\"a\"}");
        ToolCall write2 = new ToolCall("c2", "write_file", "{\"path\":\"./pom.xml\",\"content\":\"b\"}");

        assertTrue(ParallelEligibilityDecider.hasConflict(write1, write2, registry), "写入同一文件应当冲突");
    }

    @Test
    void executeCommandHasExclusiveConflictWithOtherTools() {
        ToolCall cmdCall = new ToolCall("c1", "execute_command", "{\"command\":\"echo 1\"}");
        ToolCall readCall = new ToolCall("c2", "read_file", "{\"path\":\"a.txt\"}");

        assertTrue(ParallelEligibilityDecider.hasConflict(cmdCall, readCall, registry), "Shell命令具有排他性，与其它工具冲突");
    }
}
