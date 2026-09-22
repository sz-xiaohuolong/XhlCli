package com.xhlcli.browser;

import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * 默认浏览器连接器实现。
 */
public class DefaultBrowserConnector implements BrowserConnector {

    private final BrowserSession session;
    private final BrowserConnectivityCheck connectivityCheck;
    private final SensitivePagePolicy sensitivePagePolicy;

    public DefaultBrowserConnector() {
        this(new BrowserSession(), new BrowserConnectivityCheck(), new SensitivePagePolicy());
    }

    public DefaultBrowserConnector(
            BrowserSession session,
            BrowserConnectivityCheck connectivityCheck,
            SensitivePagePolicy sensitivePagePolicy) {
        this.session = Objects.requireNonNull(session, "session");
        this.connectivityCheck = Objects.requireNonNull(connectivityCheck, "connectivityCheck");
        this.sensitivePagePolicy = Objects.requireNonNull(sensitivePagePolicy, "sensitivePagePolicy");
    }

    @Override
    public BrowserSession session() {
        return session;
    }

    @Override
    public String connectDefault() {
        return connect(9222);
    }

    @Override
    public String connect(int port) {
        var probe = connectivityCheck.probe(port);
        if (!probe.ok()) {
            return "❌ 无法连接到宿主 Chromium (端口 " + port + "): " + probe.message() + "。\n" +
                    "请确认 Chrome 启动时带有 --remote-debugging-port=" + port + " 参数。";
        }
        session.switchToShared(probe.browserUrl());
        List<BrowserTabInfo> tabs = connectivityCheck.fetchTabs(port);
        return "✅ 已成功接入宿主 Chromium (端口 " + port + ")。\n" +
                "当前模式: SHARED (共享宿主模式)\n" +
                "探测到当前打开的标签页数量: " + tabs.size() + "。宿主现有页面将受到安全保护，避免误关。";
    }

    @Override
    public String disconnect() {
        session.switchToIsolated();
        return "🔌 已断开与宿主 Chromium 的共享会话，模式已切回 ISOLATED (隔离沙箱模式)。";
    }

    @Override
    public String status() {
        StringBuilder sb = new StringBuilder();
        sb.append("🌐 浏览器会话状态：\n");
        sb.append("- 当前模式: ").append(session.mode()).append("\n");
        if (session.mode() == BrowserMode.SHARED) {
            sb.append("- 调试端点: ").append(session.browserUrl() != null ? session.browserUrl() : "无").append("\n");
            sb.append("- Agent 自建标签页数: ").append(session.agentOpenedTabs().size()).append("\n");
        } else {
            sb.append("- 模式说明: 独立沙箱模式，Agent 操作与用户日常浏览完全物理隔离。\n");
        }
        if (session.lastNavigatedUrl() != null) {
            sb.append("- 最近访问 URL: ").append(session.lastNavigatedUrl()).append("\n");
            boolean sensitive = sensitivePagePolicy.isSensitive(session.lastNavigatedUrl());
            sb.append("- 页面敏感度: ").append(sensitive ? "⚠️ 敏感页面 (受写操作硬审批保护)" : "安全/普通页面").append("\n");
        }
        return sb.toString().trim();
    }

    @Override
    public List<BrowserTabInfo> listTabs() {
        if (session.mode() != BrowserMode.SHARED || session.browserUrl() == null) {
            return List.of();
        }
        try {
            URI uri = URI.create(session.browserUrl());
            int port = uri.getPort() > 0 ? uri.getPort() : 9222;
            return connectivityCheck.fetchTabs(port);
        } catch (Exception e) {
            return List.of();
        }
    }
}
