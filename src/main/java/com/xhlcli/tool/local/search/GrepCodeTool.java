package com.xhlcli.tool.local.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.tool.Tool;
import com.xhlcli.model.ToolDefinition;
import com.xhlcli.model.ToolMetadata;
import com.xhlcli.model.ToolMetadata.RiskLevel;
import com.xhlcli.model.ToolOutput;
import com.xhlcli.tool.local.WorkspacePathResolver;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class GrepCodeTool implements Tool {
    public static ToolDefinition createDefinition() {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("type", "object");
        
        ObjectNode props = params.putObject("properties");
        props.putObject("pattern").put("type", "string").put("description", "要搜索的关键字或正则");
        props.putObject("path").put("type", "string").put("description", "搜索起始目录，默认 .");
        props.putObject("glob").put("type", "string").put("description", "可选文件 glob 过滤，例如 **/*.java");
        props.putObject("regex").put("type", "boolean").put("description", "是否按 Java 正则解释 pattern，默认 false");
        props.putObject("case_sensitive").put("type", "boolean").put("description", "是否大小写敏感，默认 true");
        props.putObject("context_lines").put("type", "integer").put("description", "每条命中前后上下文行数，默认 0，上限 5");
        props.putObject("max_results").put("type", "integer").put("description", "最多返回命中数，默认 50，上限 200");
        props.putObject("head_limit").put("type", "integer").put("description", "单个文件最多返回多少条命中，默认 20，上限 50");
        props.putObject("max_chars").put("type", "integer").put("description", "单次工具结果字符预算，默认 24000，上限 60000");

        params.putArray("required").add("pattern");

        ToolMetadata meta = new ToolMetadata(RiskLevel.LOW, true, false, true, "grep");
        return new ToolDefinition("grep_code", "在项目内按关键字或正则实时搜索代码（只读、优先 ripgrep、返回文件和行号）；适合精确符号/字符串定位，找到后再 read_file 读取上下文", params, meta);
    }

    private final WorkspacePathResolver resolver;
    private final CodeSearchEngine searchEngine;

    public GrepCodeTool(WorkspacePathResolver resolver) {
        this(resolver, new RipgrepCodeSearchEngine());
    }

    public GrepCodeTool(WorkspacePathResolver resolver, CodeSearchEngine searchEngine) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.searchEngine = Objects.requireNonNull(searchEngine, "searchEngine");
    }

    @Override
    public ToolDefinition definition() {
        return createDefinition();
    }

    @Override
    public ToolOutput execute(JsonNode arguments, CancellationToken cancellationToken) throws Exception {
        String pattern = arguments.get("pattern").asText();
        if (pattern == null || pattern.isBlank()) {
            return new ToolOutput("代码搜索失败: pattern 不能为空", JsonNodeFactory.instance.objectNode(), "");
        }
        String path = arguments.has("path") ? arguments.get("path").asText() : ".";
        String glob = arguments.has("glob") && !arguments.get("glob").isNull() ? arguments.get("glob").asText() : null;
        boolean regex = arguments.has("regex") && arguments.get("regex").asBoolean();
        boolean caseSensitive = !arguments.has("case_sensitive") || arguments.get("case_sensitive").asBoolean();
        int contextLines = arguments.has("context_lines") ? Math.max(0, Math.min(arguments.get("context_lines").asInt(), 5)) : 0;
        int maxResults = arguments.has("max_results") ? Math.max(1, Math.min(arguments.get("max_results").asInt(), 200)) : 50;
        int headLimit = arguments.has("head_limit") ? Math.max(1, Math.min(arguments.get("head_limit").asInt(), 50)) : 20;
        int maxChars = arguments.has("max_chars") ? Math.max(1000, Math.min(arguments.get("max_chars").asInt(), 60000)) : 24000;

        Path root = resolver.resolveSafe(path);
        
        CodeSearchRequest req = new CodeSearchRequest(
                pattern, root, resolver.projectRoot(), glob, regex, caseSensitive, contextLines, maxResults, headLimit
        );
        
        CodeSearchResult result = searchEngine.search(req);
        
        if (!result.partialReason().isBlank() && result.matches().isEmpty()) {
            return new ToolOutput("代码搜索失败: " + result.partialReason(), JsonNodeFactory.instance.objectNode(), "");
        }
        if (result.matches().isEmpty()) {
            StringBuilder emptyMsg = new StringBuilder();
            emptyMsg.append("未找到匹配内容: \"").append(pattern).append("\"");
            if (glob != null && !glob.isBlank()) {
                emptyMsg.append(" (限定 glob: ").append(glob).append(")");
            }
            emptyMsg.append("\n建议：");
            if (caseSensitive) {
                emptyMsg.append("\n- 尝试设置 case_sensitive 为 false 忽略大小写再次搜索");
            }
            if (glob != null && !glob.isBlank()) {
                emptyMsg.append("\n- 尝试放宽或移除 glob 路径/后缀限制");
            }
            emptyMsg.append("\n- 尝试缩短 pattern 关键字，或先使用 glob_files 定位候选文件");
            emptyMsg.append("\n- 检查是否需要切换 regex 正则模式或固定字符串模式");
            return new ToolOutput(emptyMsg.toString(), JsonNodeFactory.instance.objectNode(), "");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("匹配结果 ").append(result.matches().size()).append(" 条")
                .append(" (engine=").append(result.engine()).append(")");
        if (result.partial()) {
            sb.append("（partial: ").append(result.partialReason()).append("）");
        }
        sb.append(":\n");

        boolean truncatedByChars = false;
        int rendered = 0;
        for (int i = 0; i < result.matches().size(); i++) {
            GrepMatch match = result.matches().get(i);
            String matchHeader = (i + 1) + ". " + match.file() + ":" + match.lineNumber() + "\n";
            if (sb.length() + matchHeader.length() > maxChars) {
                truncatedByChars = true;
                break;
            }
            sb.append(i + 1).append(". ").append(match.file()).append(":").append(match.lineNumber()).append("\n");
            for (ContextLine line : match.context()) {
                String marker = line.lineNumber() == match.lineNumber() ? ">" : " ";
                String contextLine = String.format("   %s%5d | %s%n", marker, line.lineNumber(), line.text());
                if (sb.length() + contextLine.length() > maxChars) {
                    truncatedByChars = true;
                    break;
                }
                sb.append(contextLine);
            }
            rendered++;
            if (truncatedByChars) {
                break;
            }
        }
        if (truncatedByChars) {
            sb.append("\npartial: true（已达到 max_chars=").append(maxChars).append("，请缩小 path/glob/pattern 或提高 offset 后 read_file）");
        } else if (result.partial()) {
            sb.append("\npartial: true（").append(result.partialReason()).append("，请缩小 path/glob/pattern 继续搜索）");
        }
        appendSuggestedReads(sb, result.matches().subList(0, Math.min(rendered, result.matches().size())));

        return new ToolOutput(sb.toString().trim(), JsonNodeFactory.instance.objectNode(), "");
    }

    private void appendSuggestedReads(StringBuilder sb, List<GrepMatch> matches) {
        if (matches.isEmpty()) {
            return;
        }
        sb.append("\nsuggested_reads:");
        Set<String> seen = new LinkedHashSet<>();
        for (GrepMatch match : matches) {
            if (seen.size() >= 3 || !seen.add(match.file())) {
                continue;
            }
            int offset = Math.max(1, match.lineNumber() - 20);
            sb.append("\n- read_file {\"path\":\"")
                    .append(match.file().replace("\\", "\\\\").replace("\"", "\\\""))
                    .append("\",\"offset\":").append(offset)
                    .append(",\"limit\":80}");
        }
    }
}
