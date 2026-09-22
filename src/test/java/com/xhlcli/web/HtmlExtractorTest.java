package com.xhlcli.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HtmlExtractorTest {

    @Test
    void testArticleExtraction() {
        HtmlExtractor extractor = new HtmlExtractor();
        String html = """
                <!DOCTYPE html>
                <html>
                <head>
                    <title>Java 21 Virtual Threads</title>
                </head>
                <body>
                    <header>
                        <nav><a href="/">Home</a></nav>
                    </header>
                    <article>
                        <h1>Java 21 Virtual Threads</h1>
                        <p>Virtual threads are lightweight threads that dramatically reduce the effort of writing high-throughput concurrent applications.</p>
                        <h2>Key Benefits</h2>
                        <ul>
                            <li>Lightweight</li>
                            <li>High throughput</li>
                        </ul>
                        <p>Read more at <a href="https://openjdk.org/jeps/444">JEP 444</a>.</p>
                    </article>
                    <div class="ads-banner">Buy our product!</div>
                    <footer>Footer copyright info</footer>
                </body>
                </html>
                """;

        HtmlExtractor.Extracted extracted = extractor.extract(html, "https://example.com");
        assertEquals("Java 21 Virtual Threads", extracted.title());
        assertTrue(extracted.markdown().contains("# Java 21 Virtual Threads"));
        assertTrue(extracted.markdown().contains("## Key Benefits"));
        assertTrue(extracted.markdown().contains("- Lightweight"));
        assertTrue(extracted.markdown().contains("- High throughput"));
        assertTrue(extracted.markdown().contains("[JEP 444](https://openjdk.org/jeps/444)"));
        // Noise tags should be stripped
        assertFalse(extracted.markdown().contains("Home"));
        assertFalse(extracted.markdown().contains("Buy our product"));
        assertFalse(extracted.markdown().contains("Footer copyright info"));
    }

    @Test
    void testTableAndCodeBlockExtraction() {
        HtmlExtractor extractor = new HtmlExtractor();
        String html = """
                <html>
                <body>
                    <main>
                        <h1>Data Overview</h1>
                        <table>
                            <tr><th>Name</th><th>Role</th></tr>
                            <tr><td>Alice</td><td>Developer</td></tr>
                            <tr><td>Bob</td><td>Designer</td></tr>
                        </table>
                        <pre><code>System.out.println("Hello World");</code></pre>
                    </main>
                </body>
                </html>
                """;
        HtmlExtractor.Extracted extracted = extractor.extract(html, "https://example.com");
        assertTrue(extracted.markdown().contains("| Name | Role |"));
        assertTrue(extracted.markdown().contains("| Alice | Developer |"));
        assertTrue(extracted.markdown().contains("```"));
        assertTrue(extracted.markdown().contains("System.out.println(\"Hello World\");"));
    }

    @Test
    void testEmptyAndNullHtml() {
        HtmlExtractor extractor = new HtmlExtractor();
        assertEquals("", extractor.extract(null, null).markdown());
        assertEquals("", extractor.extract("", null).markdown());
        assertEquals("", extractor.extract("   ", null).markdown());
    }
}
