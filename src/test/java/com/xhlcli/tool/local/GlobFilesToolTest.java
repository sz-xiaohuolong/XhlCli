package com.xhlcli.tool.local;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.ToolOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobFilesToolTest {
    @Test
    void testGlobFiles(@TempDir Path tempDir) throws Exception {
        WorkspacePathResolver resolver = new WorkspacePathResolver(tempDir);
        GlobFilesTool tool = new GlobFilesTool(resolver);
        
        Path src = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(src);
        Files.writeString(src.resolve("UserService.java"), "class UserService {}");
        Files.writeString(tempDir.resolve("README.md"), "# Readme");
        Files.createDirectories(tempDir.resolve("node_modules"));
        Files.writeString(tempDir.resolve("node_modules/bad.js"), "console.log();");

        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("pattern", "**/*Service.java");
        
        ToolOutput out = tool.execute(args, new CancellationToken());
        assertTrue(out.summary().contains("UserService.java"));
        
        args.put("pattern", "README.md");
        out = tool.execute(args, new CancellationToken());
        assertTrue(out.summary().contains("README.md"));
        
        args.put("pattern", "*.js");
        out = tool.execute(args, new CancellationToken());
        assertTrue(!out.summary().contains("bad.js"));
    }
}
