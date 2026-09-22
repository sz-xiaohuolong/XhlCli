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
import com.xhlcli.web.SearchProvider;
import com.xhlcli.web.SearchProviderFactory;
import com.xhlcli.web.SearchResult;

import java.util.List;
import java.util.Objects;

/**
 * web_search 互联网检索工具。
 */
public final class WebSearchTool implements Tool {

    private static final ToolDefinition DEFINITION = createDefinition();

    private final SearchProvider searchProvider;

    public WebSearchTool() {
        this(SearchProviderFactory.create());
    }

    public WebSearchTool(SearchProvider searchProvider) {
        this.searchProvider = Objects.requireNonNull(searchProvider, "searchProvider");
    }

    private static ToolDefinition createDefinition() {
        ObjectNode parameters = JsonNodeFactory.instance.objectNode();
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");

        ObjectNode queryNode = properties.putObject("query");
        queryNode.put("type", "string");
        queryNode.put("description", "搜索关键词，例如 'Java 21 新特性'、'Spring Boot 3.3 release notes'");

        ObjectNode topKNode = properties.putObject("top_k");
        topKNode.put("type", "integer");
        topKNode.put("description", "返回结果数量，默认 5");

        ArrayNode required = parameters.putArray("required");
        required.add("query");

        ToolMetadata metadata = new ToolMetadata(RiskLevel.LOW, true, false, true, "web");
        return new ToolDefinition(
                "web_search",
                "搜索互联网，获取实时信息（最新版本、官方文档、技术资讯等）。仅在本地代码或上下文不足时使用。",
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

        String query = arguments.path("query").asText("").trim();
        if (query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        int topK = arguments.path("top_k").asInt(5);
        if (topK <= 0) topK = 5;

        if (!searchProvider.isReady()) {
            String hint = searchProvider.unavailableHint();
            return new ToolOutput(hint, JsonNodeFactory.instance.textNode(hint), "");
        }

        List<SearchResult> results = searchProvider.search(query, topK);
        if (results.isEmpty()) {
            String msg = "未找到关于 \"" + query + "\" 的相关搜索结果。";
            return new ToolOutput(msg, JsonNodeFactory.instance.textNode(msg), "");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("🔍 搜索结果（共 ").append(results.size()).append(" 条）：\n\n");
        ArrayNode dataArray = JsonNodeFactory.instance.arrayNode();

        for (SearchResult r : results) {
            sb.append(r.position()).append(". [").append(r.title()).append("](").append(r.url()).append(")");
            if (r.source() != null && !r.source().isBlank()) {
                sb.append(" - ").append(r.source());
            }
            if (r.publishedDate() != null && !r.publishedDate().isBlank()) {
                sb.append(" (").append(r.publishedDate()).append(")");
            }
            sb.append("\n");
            if (r.snippet() != null && !r.snippet().isBlank()) {
                sb.append("   ").append(r.snippet()).append("\n");
            }
            sb.append("\n");

            ObjectNode item = dataArray.addObject();
            item.put("position", r.position());
            item.put("title", r.title());
            item.put("url", r.url());
            item.put("snippet", r.snippet());
            item.put("source", r.source());
            if (r.publishedDate() != null) {
                item.put("publishedDate", r.publishedDate());
            }
        }

        return new ToolOutput(sb.toString().trim(), dataArray, "");
    }
}
