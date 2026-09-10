package com.xhlcli.tool.local.search;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.ToolOutput;
import com.xhlcli.rag.CodeChunk;
import com.xhlcli.rag.VectorStore;
import com.xhlcli.tool.local.WorkspacePathResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SearchCodeToolTest {

    @TempDir
    Path tempDir;

    private WorkspacePathResolver resolver;
    private VectorStore store;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("xhlcli.rag.dir", tempDir.resolve(".xhlcli").resolve("rag").toString());
        System.setProperty("xhlcli.embedding.provider", "fake");
        resolver = new WorkspacePathResolver(tempDir);
        store = new VectorStore(tempDir.toAbsolutePath().normalize().toString());
        store.clearProject();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void testSearchWhenNotIndexed() throws Exception {
        SearchCodeTool tool = new SearchCodeTool(resolver);
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("query", "login service");

        ToolOutput output = tool.execute(args, new CancellationToken());
        assertTrue(output.summary().contains("尚未索引"));
    }

    @Test
    void testSearchAfterIndexed() throws Exception {
        CodeChunk chunk = CodeChunk.methodChunk("AuthService.java", "AuthService.login",
                "public boolean login(String username, String password) { return true; }", 10, 20);
        store.insertChunks(List.of(new VectorStore.CodeChunkEntry(chunk, new float[128])));

        SearchCodeTool tool = new SearchCodeTool(resolver);
        ObjectNode args = JsonNodeFactory.instance.objectNode();
        args.put("query", "login");

        ToolOutput output = tool.execute(args, new CancellationToken());
        assertTrue(output.summary().contains("AuthService.login"));
        assertTrue(output.data().has("hits"));
    }
}
