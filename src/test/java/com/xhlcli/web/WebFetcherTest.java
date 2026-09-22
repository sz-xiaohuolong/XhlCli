package com.xhlcli.web;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class WebFetcherTest {

    private MockWebServer server;
    private NetworkPolicy testPolicy;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        // allowLocalhost = true specifically so MockWebServer (127.0.0.1) can be tested
        testPolicy = new NetworkPolicy(60_000L, 30, true);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void testSuccessfulHtmlFetch() throws IOException {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<html><head><title>Test Page</title></head><body><article><h1>Headline</h1><p>Content text</p></article></body></html>"));

        WebFetcher fetcher = new WebFetcher(testPolicy, new HtmlExtractor(), WebFetcher.DEFAULT_MAX_BYTES);
        FetchResult result = fetcher.fetch(server.url("/article").toString());

        assertNotNull(result);
        assertEquals(200, result.statusCode());
        assertEquals("Test Page", result.title());
        assertTrue(result.markdown().contains("# Headline"));
        assertTrue(result.markdown().contains("Content text"));
        assertFalse(result.truncated());
        assertNotNull(result.fetchDate());
    }

    @Test
    void testFollowRedirects() throws IOException {
        server.enqueue(new MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "/target"));
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<html><body><main><h1>Target Page</h1></main></body></html>"));

        WebFetcher fetcher = new WebFetcher(testPolicy, new HtmlExtractor(), WebFetcher.DEFAULT_MAX_BYTES);
        FetchResult result = fetcher.fetch(server.url("/source").toString());

        assertEquals(200, result.statusCode());
        assertTrue(result.finalUrl().endsWith("/target"));
        assertTrue(result.markdown().contains("Target Page"));
    }

    @Test
    void testTruncateOverMaxBytes() throws IOException {
        StringBuilder big = new StringBuilder("<html><body><main>");
        for (int i = 0; i < 1000; i++) {
            big.append("<p>Paragraph number ").append(i).append(" with some text content</p>");
        }
        big.append("</main></body></html>");

        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody(big.toString()));

        // Limit to 1024 bytes
        WebFetcher fetcher = new WebFetcher(testPolicy, new HtmlExtractor(), 1024);
        FetchResult result = fetcher.fetch(server.url("/big").toString());

        assertTrue(result.truncated());
    }

    @Test
    void testHttpErrorStatus() {
        server.enqueue(new MockResponse().setResponseCode(404));
        WebFetcher fetcher = new WebFetcher(testPolicy, new HtmlExtractor(), WebFetcher.DEFAULT_MAX_BYTES);
        IOException ex = assertThrows(IOException.class, () -> fetcher.fetch(server.url("/not-found").toString()));
        assertTrue(ex.getMessage().contains("404"));
    }

    @Test
    void testBlockedInitialUrlWithDefaultPolicy() {
        // Default policy blocks localhost / loopback
        WebFetcher defaultFetcher = new WebFetcher();
        IOException ex = assertThrows(IOException.class, () -> defaultFetcher.fetch("http://127.0.0.1:9090/"));
        assertTrue(ex.getMessage().contains("安全策略拦截"));
    }
}
