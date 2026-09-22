package com.xhlcli.web.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.ToolOutput;
import com.xhlcli.web.FetchResult;
import com.xhlcli.web.SearchProvider;
import com.xhlcli.web.SearchResult;
import com.xhlcli.web.WebFetcher;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WebToolIntegrationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testWebSearchToolExecution() throws Exception {
        SearchProvider mockProvider = new SearchProvider() {
            @Override
            public String name() {
                return "mock";
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public String unavailableHint() {
                return "Mock unavailable";
            }

            @Override
            public List<SearchResult> search(String query, int topK) {
                return List.of(
                        SearchResult.of(1, "Test Result 1", "https://example.com/1", "Snippet 1"),
                        SearchResult.of(2, "Test Result 2", "https://example.com/2", "Snippet 2")
                );
            }
        };

        WebSearchTool tool = new WebSearchTool(mockProvider);
        assertEquals("web_search", tool.definition().name());

        ObjectNode args = mapper.createObjectNode();
        args.put("query", "Java 21");
        args.put("top_k", 2);

        ToolOutput output = tool.execute(args, new CancellationToken());
        assertNotNull(output);
        assertTrue(output.summary().contains("🔍 搜索结果"));
        assertTrue(output.summary().contains("[Test Result 1](https://example.com/1)"));
        assertTrue(output.summary().contains("[Test Result 2](https://example.com/2)"));
    }

    @Test
    void testWebFetchToolExecution() throws Exception {
        WebFetcher mockFetcher = new WebFetcher() {
            @Override
            public FetchResult fetch(String url) throws IOException {
                return FetchResult.of(url, url, 200, "Example Title", "# Header\n\nParagraph text", false);
            }
        };

        WebFetchTool tool = new WebFetchTool(mockFetcher);
        assertEquals("web_fetch", tool.definition().name());

        ObjectNode args = mapper.createObjectNode();
        args.put("url", "https://example.com");

        ToolOutput output = tool.execute(args, new CancellationToken());
        assertNotNull(output);
        assertTrue(output.summary().contains("# Example Title"));
        assertTrue(output.summary().contains("**来源**: [https://example.com](https://example.com)"));
        assertTrue(output.summary().contains("Paragraph text"));
    }

    @Test
    void testWebFetchToolEmptyContentBrowserDegradationHint() throws Exception {
        WebFetcher mockFetcher = new WebFetcher() {
            @Override
            public FetchResult fetch(String url) throws IOException {
                return FetchResult.of(url, url, 200, "Empty SPA", "", false);
            }
        };

        WebFetchTool tool = new WebFetchTool(mockFetcher);
        ObjectNode args = mapper.createObjectNode();
        args.put("url", "https://spa.example.com");

        ToolOutput output = tool.execute(args, new CancellationToken());
        assertTrue(output.summary().contains("⚠️ 网页正文为空"));
        assertTrue(output.summary().contains("建议使用浏览器工具"));
    }
}
