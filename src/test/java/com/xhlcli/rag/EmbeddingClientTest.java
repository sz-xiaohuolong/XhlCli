package com.xhlcli.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddingClientTest {

    @Test
    void testDefaultConfiguration() {
        EmbeddingClient client = new EmbeddingClient();
        assertNotNull(client.getProvider());
        assertNotNull(client.getModel());
    }

    @Test
    void testCustomConfiguration() {
        EmbeddingClient client = new EmbeddingClient("zhipu", "embedding-3",
                "https://open.bigmodel.cn/api/paas/v4", "test-key");
        assertEquals("zhipu", client.getProvider());
        assertEquals("embedding-3", client.getModel());
        assertEquals("https://open.bigmodel.cn/api/paas/v4", client.getBaseUrl());
    }

    @Test
    void testEmptyInputReturnsEmptyArray() throws Exception {
        EmbeddingClient client = EmbeddingClient.fake();
        assertEquals(0, client.embed("").length);
        assertEquals(0, client.embed(null).length);
    }

    @Test
    void testFakeEmbeddingConsistency() throws Exception {
        EmbeddingClient client = EmbeddingClient.fake();
        float[] emb1 = client.embed("public class TestService");
        float[] emb2 = client.embed("public class TestService");
        float[] emb3 = client.embed("unrelated random text string");

        assertEquals(128, emb1.length);
        assertArrayEquals(emb1, emb2);
        assertNotEquals(emb1[0], emb3[0]);
    }
}
