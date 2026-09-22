package com.xhlcli.browser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BrowserSessionTest {

    @Test
    void testInitialStateIsIsolated() {
        BrowserSession session = new BrowserSession();
        assertEquals(BrowserMode.ISOLATED, session.mode());
        assertNull(session.browserUrl());
        assertNull(session.lastNavigatedUrl());
        assertTrue(session.agentOpenedTabs().isEmpty());
    }

    @Test
    void testSwitchToSharedAndBack() {
        BrowserSession session = new BrowserSession();
        session.switchToShared("http://127.0.0.1:9222");
        assertEquals(BrowserMode.SHARED, session.mode());
        assertEquals("http://127.0.0.1:9222", session.browserUrl());

        session.recordOpenedTab("page-123");
        assertTrue(session.isAgentOpenedTab("page-123"));
        assertFalse(session.isAgentOpenedTab("page-999"));

        session.switchToIsolated();
        assertEquals(BrowserMode.ISOLATED, session.mode());
        assertNull(session.browserUrl());
        assertFalse(session.isAgentOpenedTab("page-123"));
    }

    @Test
    void testNavigationTracking() {
        BrowserSession session = new BrowserSession();
        session.rememberNavigation("https://example.com/docs");
        assertEquals("https://example.com/docs", session.lastNavigatedUrl());
    }
}
