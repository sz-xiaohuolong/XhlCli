package com.xhlcli.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SearchProviderFactoryTest {

    @Test
    void testExplicitProviderSelection() {
        assertEquals("searxng", SearchProviderFactory.pickProvider("searxng", "key", "url", "zkey"));
        assertEquals("serpapi", SearchProviderFactory.pickProvider("serpapi", null, null, null));
        assertEquals("zhipu", SearchProviderFactory.pickProvider("ZHIPU", null, null, null));
        assertEquals("duckduckgo", SearchProviderFactory.pickProvider("duckduckgo", "key", null, null));
    }

    @Test
    void testAutomaticSelectionByCredentials() {
        assertEquals("serpapi", SearchProviderFactory.pickProvider(null, "serp-123", null, null));
        assertEquals("searxng", SearchProviderFactory.pickProvider(null, null, "http://localhost:8888", null));
        assertEquals("zhipu", SearchProviderFactory.pickProvider(null, null, null, "zhipu-456"));
        assertEquals("duckduckgo", SearchProviderFactory.pickProvider(null, null, null, null));
    }

    @Test
    void testFactoryCreateDefault() {
        SearchProvider provider = SearchProviderFactory.create();
        assertNotNull(provider);
        assertNotNull(provider.name());
    }
}
