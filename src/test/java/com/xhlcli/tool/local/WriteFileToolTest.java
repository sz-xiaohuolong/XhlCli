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

public class WriteFileToolTest {

    @TempDir
    Path tempDir;

    @Test
    public void testCreatingNewFile() throws Exception {
        WorkspacePathResolver resolver = new WorkspacePathResolver(tempDir);
        WriteFileTool tool = new WriteFileTool(resolver);

        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("path", "newdir/newfile.txt");
        args.put("content", "hello world");
        
        ToolOutput output = tool.execute(args, new CancellationToken());

        Path created = tempDir.resolve("newdir/newfile.txt");
        assertTrue(Files.exists(created));
        assertEquals("hello world", Files.readString(created));
        assertTrue(output.summary().contains("文件已写入"));
    }

    @Test
    public void testOverwriteExistingFile() throws Exception {
        Path file = tempDir.resolve("existing.txt");
        Files.writeString(file, "old content");

        WorkspacePathResolver resolver = new WorkspacePathResolver(tempDir);
        WriteFileTool tool = new WriteFileTool(resolver);

        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("path", "existing.txt");
        args.put("content", "new content");
        
        tool.execute(args, new CancellationToken());

        assertEquals("new content", Files.readString(file));
    }

    @Test
    public void testSizeLimitRejection() {
        WorkspacePathResolver resolver = new WorkspacePathResolver(tempDir);
        WriteFileTool tool = new WriteFileTool(resolver);

        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("path", "large.txt");
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6 * 1024 * 1024; i++) {
            sb.append("a");
        }
        args.put("content", sb.toString());
        
        assertThrows(Exception.class, () -> {
            tool.execute(args, new CancellationToken());
        });
    }
}
