package com.xhlcli.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodeIndexTest {

    @BeforeEach
    void setUp() {
        System.setProperty("xhlcli.rag.dir", "/tmp/xhlcli-test-rag-index");
        System.setProperty("xhlcli.embedding.provider", "fake");
    }

    @Test
    void testIndexNonExistentPath() {
        CodeIndex indexer = new CodeIndex(EmbeddingClient.fake(), CodeIndex.ProgressListener.noop());
        CodeIndex.IndexResult result = indexer.index("/non/existent/path");
        assertEquals(0, result.chunkCount());
        assertTrue(result.message().contains("路径不存在"));
    }

    @Test
    void testIndexCurrentProject() {
        CodeIndex indexer = new CodeIndex(EmbeddingClient.fake(), CodeIndex.ProgressListener.noop());
        // 索引测试资源目录
        CodeIndex.IndexResult result = indexer.index("src/test/resources/rag", true);
        assertTrue(result.chunkCount() > 0, "应该至少索引一个代码块");
        assertTrue(result.message().contains("索引完成"));
        assertTrue(result.updatedFiles() > 0);
    }

    @Test
    void reportsProgressThroughListener() {
        List<String> messages = new ArrayList<>();
        CodeIndex indexer = new CodeIndex(EmbeddingClient.fake(), messages::add);

        CodeIndex.IndexResult result = indexer.index("src/test/resources/rag", true);

        assertTrue(result.chunkCount() > 0, "应该至少索引一个代码块");
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("🔍 开始索引")));
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("📁 发现")));
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("✅ 索引完成")));
    }

    @Test
    void testIncrementalIndexSkipsUnchangedFiles() {
        CodeIndex indexer = new CodeIndex(EmbeddingClient.fake(), CodeIndex.ProgressListener.noop());

        // 第一次全量索引
        CodeIndex.IndexResult first = indexer.index("src/test/resources/rag", true);
        assertTrue(first.updatedFiles() > 0);
        assertEquals(0, first.skippedFiles());

        // 第二次增量索引，内容未修改，应该全部跳过
        CodeIndex.IndexResult second = indexer.index("src/test/resources/rag", false);
        assertEquals(0, second.updatedFiles());
        assertTrue(second.skippedFiles() > 0);

        // 测试 status 和 clean
        VectorStore.IndexStats stats = indexer.getStatus("src/test/resources/rag");
        assertEquals(first.chunkCount(), stats.chunkCount());

        boolean cleanOk = indexer.clean("src/test/resources/rag");
        assertTrue(cleanOk);
        VectorStore.IndexStats clearedStats = indexer.getStatus("src/test/resources/rag");
        assertEquals(0, clearedStats.chunkCount());
    }
}
