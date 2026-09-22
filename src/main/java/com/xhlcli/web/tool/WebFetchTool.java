package com.xhlcli.web.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.ToolDefinition;
import com.xhlcli.model.ToolMetadata;
import com.xhlcli.model.ToolMetadata.RiskLevel;
import com.xhlcli.model.ToolOutput;
import com.xhlcli.tool.Tool;
import com.xhlcli.web.FetchResult;
import com.xhlcli.web.WebFetcher;

import java.util.Objects;

/**
 * web_fetch 网页抓取与正文抽取工具。
 */
public final class WebFetchTool implements Tool {

    private static final ToolDefinition DEFINITION = createDefinition();
    public static final int DEFAULT_MAX_CHARS = 8000;

    private final WebFetcher webFetcher;

    public WebFetchTool() {
        this(new WebFetcher());
    }

    public WebFetchTool(WebFetcher webFetcher) {
        this.webFetcher = Objects.requireNonNull(webFetcher, "webFetcher");
    }

    private static ToolDefinition createDefinition() {
        ObjectNode parameters = JsonNodeFactory.instance.objectNode();
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");

        ObjectNode urlNode = properties.putObject("url");
        urlNode.put("type", "string");
        urlNode.put("description", "完整网页 URL，需 http 或 https 协议");

        ObjectNode maxCharsNode = properties.putObject("max_chars");
        maxCharsNode.put("type", "integer");
        maxCharsNode.put("description", "返回 Markdown 最大字符数，默认 8000，超出平滑截断");

        ArrayNode required = parameters.putArray("required");
        required.add("url");

        ToolMetadata metadata = new ToolMetadata(RiskLevel.LOW, true, false, true, "web");
        return new ToolDefinition(
                "web_fetch",
                "抓取指定 URL，提取正文转 Markdown。适用静态/SSR 页面；JS 渲染页面或防爬拦截返回空正文时，建议使用浏览器工具。",
                parameters,
                metadata
        );
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolOutput execute(JsonNode arguments, CancellationToken cancellationToken) throws Exception {
        if (cancellationToken != null && cancellationToken.isCancelled()) {
            throw new InterruptedException("操作已取消");
        }

        String url = arguments.path("url").asText("").trim();
        if (url.isBlank()) {
            throw new IllegalArgumentException("url 不能为空");
        }
        int maxChars = arguments.path("max_chars").asInt(DEFAULT_MAX_CHARS);
        if (maxChars <= 0) maxChars = DEFAULT_MAX_CHARS;

        FetchResult result = webFetcher.fetch(url);

        StringBuilder sb = new StringBuilder();
        if (!result.title().isBlank()) {
            sb.append("# ").append(result.title()).append("\n\n");
        }
        sb.append("**来源**: [").append(result.finalUrl()).append("](").append(result.finalUrl()).append(")")
                .append(" | **获取日期**: ").append(result.fetchDate()).append("\n\n");

        String markdown = result.markdown();
        if (markdown.isBlank()) {
            sb.append("⚠️ 网页正文为空。该页面可能依赖 JavaScript 渲染或存在反爬拦截，建议使用浏览器工具 (/browser 或 browser 工具族) 查看。");
        } else {
            if (markdown.length() > maxChars) {
                sb.append(markdown, 0, maxChars)
                        .append("\n\n...(正文已截断，共 ").append(markdown.length()).append(" 字符，可通过浏览器查看完整内容)");
            } else {
                sb.append(markdown);
            }
        }

        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("url", result.url());
        data.put("finalUrl", result.finalUrl());
        data.put("statusCode", result.statusCode());
        data.put("title", result.title());
        data.put("fetchDate", result.fetchDate());
        data.put("truncated", result.truncated() || markdown.length() > maxChars);

        return new ToolOutput(sb.toString().trim(), data, "");
    }
}
