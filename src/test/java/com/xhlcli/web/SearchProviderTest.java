package com.xhlcli.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SearchProviderTest {

    @Test
    void testSerpApiParser() throws IOException {
        SerpApiSearchProvider provider = new SerpApiSearchProvider("test-key");
        assertTrue(provider.isReady());
        assertEquals("serpapi", provider.name());

        String json = """
                {
                  "organic_results": [
                    {
                      "title": "Java 21 Features",
                      "link": "https://openjdk.org/projects/jdk/21/",
                      "snippet": "Java 21 is the latest LTS release.",
                      "date": "2023-09-19"
                    }
                  ]
                }
                """;
        List<SearchResult> results = provider.parse(json, 5);
        assertEquals(1, results.size());
        SearchResult r = results.get(0);
        assertEquals(1, r.position());
        assertEquals("Java 21 Features", r.title());
        assertEquals("https://openjdk.org/projects/jdk/21/", r.url());
        assertEquals("openjdk.org", r.source());
        assertEquals("Java 21 is the latest LTS release.", r.snippet());
        assertEquals("2023-09-19", r.publishedDate());

        SerpApiSearchProvider unready = new SerpApiSearchProvider("");
        assertFalse(unready.isReady());
        assertThrows(IOException.class, () -> unready.search("test", 5));
    }

    @Test
    void testSearxngParser() throws IOException {
        SearxngSearchProvider provider = new SearxngSearchProvider("http://localhost:8888");
        assertTrue(provider.isReady());
        assertEquals("searxng", provider.name());

        String json = """
                {
                  "results": [
                    {
                      "title": "Spring Boot 3.3 Release",
                      "url": "https://spring.io/blog/2024/05/23/spring-boot-3-3-0-available-now",
                      "content": "Spring Boot 3.3.0 is now available.",
                      "publishedDate": "2024-05-23"
                    }
                  ]
                }
                """;
        List<SearchResult> results = provider.parse(json, 5);
        assertEquals(1, results.size());
        SearchResult r = results.get(0);
        assertEquals("Spring Boot 3.3 Release", r.title());
        assertEquals("spring.io", r.source());
        assertEquals("2024-05-23", r.publishedDate());

        SearxngSearchProvider unready = new SearxngSearchProvider("");
        assertFalse(unready.isReady());
        assertThrows(IOException.class, () -> unready.search("test", 5));
    }

    @Test
    void testZhipuParser() throws IOException {
        ZhipuSearchProvider provider = new ZhipuSearchProvider("zhipu-key", "search_std");
        assertTrue(provider.isReady());
        assertEquals("zhipu", provider.name());

        String json = """
                {
                  "search_result": [
                    {
                      "title": "智能体系统架构设计",
                      "link": "https://example.cn/agent-arch",
                      "content": "深入解析多智能体编排与协作模式。",
                      "media": "2024-08-01"
                    }
                  ]
                }
                """;
        List<SearchResult> results = provider.parse(json, 5);
        assertEquals(1, results.size());
        SearchResult r = results.get(0);
        assertEquals("智能体系统架构设计", r.title());
        assertEquals("example.cn", r.source());

        ZhipuSearchProvider unready = new ZhipuSearchProvider("");
        assertFalse(unready.isReady());
        assertThrows(IOException.class, () -> unready.search("test", 5));
    }

    @Test
    void testDuckDuckGoParser() {
        DuckDuckGoSearchProvider provider = new DuckDuckGoSearchProvider();
        assertTrue(provider.isReady());
        assertEquals("duckduckgo", provider.name());

        String html = """
                <html><body>
                <div class="results">
                  <div class="result">
                    <h2 class="result__title"><a class="result__url" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fdocs&rut=1">Example Docs</a></h2>
                    <div class="result__snippet">This is documentation for Example.</div>
                  </div>
                </div>
                </body></html>
                """;
        List<SearchResult> results = provider.parse(html, 5);
        assertEquals(1, results.size());
        SearchResult r = results.get(0);
        assertEquals("Example Docs", r.title());
        assertEquals("https://example.com/docs", r.url());
        assertEquals("This is documentation for Example.", r.snippet());
    }
}
