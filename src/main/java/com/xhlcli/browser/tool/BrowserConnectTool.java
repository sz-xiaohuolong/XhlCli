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
 * browser_connect 连接外部浏览器 CDP 调试端点。
 */
public final class BrowserConnectTool implements Tool {

    private static final ToolDefinition DEFINITION = createDefinition();

    private final BrowserConnector browserConnector;

    public BrowserConnectTool(BrowserConnector browserConnector) {
        this.browserConnector = Objects.requireNonNull(browserConnector, "browserConnector");
    }

    private static ToolDefinition createDefinition() {
        ObjectNode parameters = JsonNodeFactory.instance.objectNode();
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");

        ObjectNode portNode = properties.putObject("port");
        portNode.put("type", "integer");
        portNode.put("description", "宿主 Chromium CDP 远程调试端口号，默认 9222");

        ToolMetadata metadata = new ToolMetadata(RiskLevel.LOW, false, false, false, "browser");
        return new ToolDefinition(
                "browser_connect",
                "连接到已启动远程调试端口 (CDP) 的宿主 Chromium 浏览器，进入 SHARED (共享宿主) 模式。",
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
        int port = 9222;
        if (arguments != null && arguments.has("port") && !arguments.get("port").isNull()) {
            port = arguments.get("port").asInt(9222);
        }
        String message = browserConnector.connect(port);
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("port", port);
        data.put("mode", browserConnector.session().mode().name());
        return new ToolOutput(message, data, "");
    }
}
