package com.xhlcli.browser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BrowserGuardTest {

    @Test
    void testNonBrowserToolsAllowed() {
        BrowserSession session = new BrowserSession();
        BrowserGuard guard = new BrowserGuard(session, new SensitivePagePolicy(null));

        BrowserCheckResult result = guard.check("read_file", "{\"path\":\"README.md\"}", false);
        assertFalse(result.blocked());
        assertFalse(result.requiresPerCallApproval());
    }

    @Test
    void testSensitivePageWriteOperationsRequirePerCallApproval() {
        BrowserSession session = new BrowserSession();
        BrowserGuard guard = new BrowserGuard(session, new SensitivePagePolicy(null));

        // Navigate to sensitive page
        guard.check("mcp__chrome-devtools__navigate_page", "{\"url\":\"https://github.com/settings/tokens\"}", true);
        assertEquals("https://github.com/settings/tokens", session.lastNavigatedUrl());

        // Read tools should be allowed
        BrowserCheckResult readCheck = guard.check("mcp__chrome-devtools__take_snapshot", "{}", false);
        assertFalse(readCheck.blocked());
        assertFalse(readCheck.requiresPerCallApproval());

        // Write tools on sensitive page must require approval
        BrowserCheckResult clickCheck = guard.check("mcp__chrome-devtools__click", "{\"selector\":\"#delete-btn\"}", false);
        assertFalse(clickCheck.blocked());
        assertTrue(clickCheck.requiresPerCallApproval());
        assertTrue(clickCheck.sensitiveNotice().contains("敏感页面命中规则"));

        BrowserCheckResult fillCheck = guard.check("mcp__chrome-devtools__fill", "{\"value\":\"secret\"}", false);
        assertTrue(fillCheck.requiresPerCallApproval());
    }

    @Test
    void testSharedModeBlocksClosingNonAgentTabs() {
        BrowserSession session = new BrowserSession();
        BrowserGuard guard = new BrowserGuard(session, new SensitivePagePolicy(null));

        session.switchToShared("http://127.0.0.1:9222");

        // Agent closes a tab that was NOT opened by agent
        BrowserCheckResult closeOtherTab = guard.check("mcp__chrome-devtools__close_page", "{\"pageId\":\"page-user-work\"}", false);
        assertTrue(closeOtherTab.blocked());
        assertTrue(closeOtherTab.reason().contains("拒绝关闭非 XhlCLI 创建的标签页"));

        // Agent creates a tab
        guard.applyAfterExecution("mcp__chrome-devtools__new_page", "{\"pageId\":\"page-agent-tab\"}", "Created page page-agent-tab");
        assertTrue(session.isAgentOpenedTab("page-agent-tab"));

        // Agent closing its own tab should be allowed
        BrowserCheckResult closeOwnTab = guard.check("mcp__chrome-devtools__close_page", "{\"pageId\":\"page-agent-tab\"}", false);
        assertFalse(closeOwnTab.blocked());
    }
}
