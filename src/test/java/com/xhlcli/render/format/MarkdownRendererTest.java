package com.xhlcli.render.format;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MarkdownRenderer Tests")
class MarkdownRendererTest {

    @Test
    @DisplayName("Should render headers, lists, bold and inline code")
    void shouldRenderBasicMarkdown() {
        String md = """
                # Main Title
                ## Sub Title
                - first item
                - second with **bold** and `code`
                """;

        String colored = MarkdownRenderer.renderColored(md, 80);
        assertTrue(colored.contains("# Main Title"));
        assertTrue(colored.contains("• first item"));
        assertTrue(colored.contains("• second with"));
        assertTrue(colored.contains("\u001B[1mbold\u001B[22m"));
        assertTrue(colored.contains("\u001B[33mcode\u001B[39m"));

        String plain = MarkdownRenderer.renderPlain(md, 80);
        assertTrue(plain.contains("# Main Title"));
        assertTrue(plain.contains("• first item"));
        assertFalse(plain.contains("\u001B["));
    }

    @Test
    @DisplayName("Should render code block with neat borders and indentation")
    void shouldRenderCodeBlock() {
        String md = """
                ```java
                System.out.println("Hello");
                ```
                """;

        String rendered = MarkdownRenderer.renderPlain(md, 80);
        assertTrue(rendered.contains("┌─── java ──"));
        assertTrue(rendered.contains("  System.out.println(\"Hello\");"));
        assertTrue(rendered.contains("└───"));
    }

    @Test
    @DisplayName("Should render table with neat aligned borders")
    void shouldRenderTable() {
        String md = """
                | Tool | Status | Duration |
                |---|---|---|
                | readFile | Success | 25ms |
                | grepCode | Success | 120ms |
                """;

        String rendered = MarkdownRenderer.renderPlain(md, 80);
        assertTrue(rendered.contains("┌"));
        assertTrue(rendered.contains("┬"));
        assertTrue(rendered.contains("┐"));
        assertTrue(rendered.contains("│ Tool"));
        assertTrue(rendered.contains("│ Status"));
        assertTrue(rendered.contains("│ Duration"));
        assertTrue(rendered.contains("├"));
        assertTrue(rendered.contains("┼"));
        assertTrue(rendered.contains("┤"));
        assertTrue(rendered.contains("│ readFile"));
        assertTrue(rendered.contains("│ grepCode"));
        assertTrue(rendered.contains("└"));
        assertTrue(rendered.contains("┴"));
        assertTrue(rendered.contains("┘"));
    }
}
