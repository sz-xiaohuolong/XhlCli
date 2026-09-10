package com.xhlcli.tool.local.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.ToolDefinition;
import com.xhlcli.model.ToolMetadata;
import com.xhlcli.model.ToolMetadata.RiskLevel;
import com.xhlcli.model.ToolOutput;
import com.xhlcli.rag.CodeRetriever;
import com.xhlcli.rag.SearchResultFormatter;
import com.xhlcli.rag.VectorStore;
import com.xhlcli.tool.Tool;
import com.xhlcli.tool.local.WorkspacePathResolver;

import java.util.List;
import java.util.Objects;

/**
 * 只读 RAG 语义与混合代码检索工具
 */
public final class SearchCodeTool implements Tool {
    public static ToolDefinition createDefinition() {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("type", "object");

        ObjectNode props = params.putObject("properties");
        props.putObject("query")
                .put("type", "string")
                .put("description", "自然语言查询描述，例如'用户登录的实现'或'增量索引检查'");
        props.putObject("top_k")
                .put("type", "integer")
                .put("description", "返回结果数量（默认 5，上限 30）");

        params.putArray("required").add("query");

        ToolMetadata meta = new ToolMetadata(RiskLevel.LOW, true, false, true, "rag");
        return new ToolDefinition(
                "search_code",
                "RAG 语义辅助检索代码库，根据自然语言描述查找相关代码块；精确符号/字符串定位请优先用 grep_code/glob_files/read_file；默认 top_k=5，可显式指定（上限 30）",
                params,
                meta
        );
    }

    private final WorkspacePathResolver resolver;

    public SearchCodeTool(WorkspacePathResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public ToolDefinition definition() {
        return createDefinition();
    }

    @Override
    public ToolOutput execute(JsonNode arguments, CancellationToken cancellationToken) throws Exception {
        if (arguments == null || !arguments.hasNonNull("query")) {
            return new ToolOutput("代码检索失败: query 不能为空", JsonNodeFactory.instance.objectNode(), "");
        }

        String query = arguments.get("query").asText().trim();
        if (query.isBlank()) {
            return new ToolOutput("代码检索失败: query 不能为空", JsonNodeFactory.instance.objectNode(), "");
        }

        int topK = 5;
        if (arguments.has("top_k") && arguments.get("top_k").isInt()) {
            topK = arguments.get("top_k").asInt();
        }
        topK = Math.max(1, Math.min(topK, 30));

        String projectPath = resolver.projectRoot().toAbsolutePath().normalize().toString();

        try (CodeRetriever retriever = new CodeRetriever(projectPath)) {
            VectorStore.IndexStats stats = retriever.getStats();
            if (stats.chunkCount() == 0) {
                return new ToolOutput("代码库尚未索引，请先使用 /index 命令索引当前项目。",
                        JsonNodeFactory.instance.objectNode(), "");
            }

            List<VectorStore.SearchResult> results = retriever.hybridSearch(query, topK);
            if (results.isEmpty()) {
                return new ToolOutput("未找到与查询相关的代码。",
                        JsonNodeFactory.instance.objectNode(), "");
            }

            String formatted = SearchResultFormatter.formatForTool(query, results);
            ObjectNode data = JsonNodeFactory.instance.objectNode();
            data.put("totalHits", results.size());
            data.put("topK", topK);
            ArrayNode hitsArray = data.putArray("hits");
            for (VectorStore.SearchResult res : results) {
                ObjectNode hitObj = hitsArray.addObject();
                hitObj.put("file", res.filePath());
                hitObj.put("type", res.chunkType());
                hitObj.put("name", res.name());
                hitObj.put("similarity", res.similarity());
            }

            return new ToolOutput(formatted, data, "");
        } catch (Exception e) {
            return new ToolOutput("代码检索失败: " + e.getMessage(),
                    JsonNodeFactory.instance.objectNode(), "");
        }
    }
}
