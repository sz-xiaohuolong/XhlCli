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
 * browser_disconnect 断开外部浏览器 CDP 会话。
 */
public final class BrowserDisconnectTool implements Tool {

    private static final ToolDefinition DEFINITION = createDefinition();

    private final BrowserConnector browserConnector;

    public BrowserDisconnectTool(BrowserConnector browserConnector) {
        this.browserConnector = Objects.requireNonNull(browserConnector, "browserConnector");
    }

    private static ToolDefinition createDefinition() {
        ObjectNode parameters = JsonNodeFactory.instance.objectNode();
        parameters.put("type", "object");
        parameters.putObject("properties");

        ToolMetadata metadata = new ToolMetadata(RiskLevel.LOW, false, false, false, "browser");
        return new ToolDefinition(
                "browser_disconnect",
                "断开与宿主浏览器的共享连接，重置浏览器模式为 ISOLATED (隔离沙箱模式)。",
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
        String message = browserConnector.disconnect();
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("mode", browserConnector.session().mode().name());
        return new ToolOutput(message, data, "");
    }
}
