package com.xhlcli.browser.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhlcli.browser.BrowserConnector;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.ToolDefinition;
import com.xhlcli.model.ToolMetadata;
import com.xhlcli.model.ToolMetadata.RiskLevel;
import com.xhlcli.model.ToolOutput;
import com.xhlcli.tool.Tool;

import java.util.Objects;

/**
 * browser_status 查看当前浏览器会话模式与状态。
 */
public final class BrowserStatusTool implements Tool {

    private static final ToolDefinition DEFINITION = createDefinition();

    private final BrowserConnector browserConnector;

    public BrowserStatusTool(BrowserConnector browserConnector) {
        this.browserConnector = Objects.requireNonNull(browserConnector, "browserConnector");
    }

    private static ToolDefinition createDefinition() {
        ObjectNode parameters = JsonNodeFactory.instance.objectNode();
        parameters.put("type", "object");
        parameters.putObject("properties");

        ToolMetadata metadata = new ToolMetadata(RiskLevel.LOW, true, false, true, "browser");
        return new ToolDefinition(
                "browser_status",
                "查看当前浏览器会话模式 (ISOLATED / SHARED)、已连接调试地址与敏感页面防护状态。",
                parameters,
                metadata
        );
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolOutput execute(JsonNode arguments, CancellationToken cancellationToken) {
        String message = browserConnector.status();
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("mode", browserConnector.session().mode().name());
        data.put("browserUrl", browserConnector.session().browserUrl());
        data.put("lastNavigatedUrl", browserConnector.session().lastNavigatedUrl());
        data.put("agentOpenedTabsCount", browserConnector.session().agentOpenedTabs().size());
        return new ToolOutput(message, data, "");
    }
}
