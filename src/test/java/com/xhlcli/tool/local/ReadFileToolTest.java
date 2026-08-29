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

public class ReadFileToolTest {

    @TempDir
    Path tempDir;

    @Test
    public void testFullFileRead() throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "line1\nline2\nline3");

        WorkspacePathResolver resolver = new WorkspacePathResolver(tempDir);
        ReadFileTool tool = new ReadFileTool(resolver);

        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("path", "test.txt");
        
        ToolOutput output = tool.execute(args, new CancellationToken());

        assertEquals("line1\nline2\nline3", output.summary());
    }

    @Test
    public void testRangedRead() throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "line1\nline2\nline3\nline4\nline5");

        WorkspacePathResolver resolver = new WorkspacePathResolver(tempDir);
        ReadFileTool tool = new ReadFileTool(resolver);

        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("path", "test.txt");
        args.put("offset", 2);
        args.put("limit", 2);
        
        ToolOutput output = tool.execute(args, new CancellationToken());

        String expected = "    2 | line2\n    3 | line3\n...(已截断，可用 offset=4 继续读取)";
        assertEquals(expected, output.summary());
    }
    
    @Test
    public void testFileNotFound() {
        WorkspacePathResolver resolver = new WorkspacePathResolver(tempDir);
        ReadFileTool tool = new ReadFileTool(resolver);

        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("path", "nonexistent.txt");
        
        assertThrows(IllegalArgumentException.class, () -> {
            tool.execute(args, new CancellationToken());
        });
    }
}
