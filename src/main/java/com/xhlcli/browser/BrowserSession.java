package com.xhlcli.browser;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 浏览器会话生命周期与状态持有。
 */
public class BrowserSession {

    private BrowserMode mode = BrowserMode.ISOLATED;
    private String browserUrl;
    private String lastNavigatedUrl;
    private final Set<String> agentOpenedTabs = new LinkedHashSet<>();

    public synchronized BrowserMode mode() {
        return mode;
    }

    public synchronized String browserUrl() {
        return browserUrl;
    }

    public synchronized String lastNavigatedUrl() {
        return lastNavigatedUrl;
    }

    public synchronized void switchToIsolated() {
        mode = BrowserMode.ISOLATED;
        browserUrl = null;
        lastNavigatedUrl = null;
        agentOpenedTabs.clear();
    }

    public synchronized void switchToShared(String browserUrl) {
        mode = BrowserMode.SHARED;
        this.browserUrl = browserUrl;
        lastNavigatedUrl = null;
        agentOpenedTabs.clear();
    }

    public synchronized void rememberNavigation(String url) {
        if (url != null && !url.isBlank()) {
            lastNavigatedUrl = url;
        }
    }

    public synchronized void recordOpenedTab(String pageId) {
        if (pageId != null && !pageId.isBlank()) {
            agentOpenedTabs.add(pageId);
        }
    }

    public synchronized void removeOpenedTab(String pageId) {
        if (pageId != null) {
            agentOpenedTabs.remove(pageId);
        }
    }

    public synchronized boolean isAgentOpenedTab(String pageId) {
        return pageId != null && agentOpenedTabs.contains(pageId);
    }

    public synchronized Set<String> agentOpenedTabs() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(agentOpenedTabs));
    }

    public synchronized void clearAgentOpenedTabs() {
        agentOpenedTabs.clear();
    }
}
