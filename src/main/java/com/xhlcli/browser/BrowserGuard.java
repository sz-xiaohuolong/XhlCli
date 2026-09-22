package com.xhlcli.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 浏览器操作安全守护者。
 *
 * <ol>
 *   <li>敏感页面改写操作（点击、输入、脚本执行等）强制触发单步审批，严禁批处理放行；</li>
 *   <li>在 SHARED 模式下严禁关闭非 Agent 自身开启的标签页，保护用户宿主 Chrome 的工作区。</li>
 * </ol>
 */
public class BrowserGuard {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CHROME_DEVTOOLS_PREFIX_HYPHEN = "mcp__chrome-devtools__";
    private static final String CHROME_DEVTOOLS_PREFIX_UNDERSCORE = "mcp__chrome_devtools__";
    private static final String PUPPETEER_PREFIX = "mcp__puppeteer__";
    private static final Set<String> WRITE_TOOLS = Set.of(
            "click",
            "drag",
            "fill",
            "fill_form",
            "handle_dialog",
            "hover",
            "press_key",
            "resize_page",
            "upload_file",
            "evaluate_script"
    );
    private static final Pattern PAGE_ID_PATTERN = Pattern.compile("(page[-_][A-Za-z0-9_-]+)");

    private final BrowserSession session;
    private final SensitivePagePolicy sensitivePagePolicy;

    public BrowserGuard(BrowserSession session, SensitivePagePolicy sensitivePagePolicy) {
        this.session = session;
        this.sensitivePagePolicy = sensitivePagePolicy;
    }

    public BrowserCheckResult check(String toolName, String argsJson, boolean mutateSession) {
        if (!isBrowserTool(toolName)) {
            return BrowserCheckResult.allow(null);
        }
        String localTool = localToolName(toolName);
        JsonNode args = parseArgs(argsJson);
        String targetUrl = targetUrl(localTool, args);
        String effectiveUrl = targetUrl == null ? session.lastNavigatedUrl() : targetUrl;
        SensitivePagePolicy.MatchResult match = sensitivePagePolicy.match(effectiveUrl);
        BrowserAuditMetadata metadata = BrowserAuditMetadata.of(session.mode(), match.matched(), effectiveUrl);

        if ("close_page".equals(localTool)
                && session.mode() == BrowserMode.SHARED
                && !session.isAgentOpenedTab(pageId(args))) {
            return BrowserCheckResult.block(
                    "shared 浏览器模式下拒绝关闭非 XhlCLI 创建的标签页，请手动关闭该 Chrome 标签页",
                    metadata);
        }

        if (match.matched() && WRITE_TOOLS.contains(localTool)) {
            return BrowserCheckResult.requireApproval(
                    "敏感页面命中规则 " + match.pattern() + "，本次浏览器改写操作必须单步审批，不能复用全部放行。",
                    metadata);
        }

        if (mutateSession) {
            applyMutation(localTool, args, targetUrl);
        }
        return BrowserCheckResult.allow(metadata);
    }

    public void applyAfterExecution(String toolName, String argsJson, String result) {
        if (!isBrowserTool(toolName)) {
            return;
        }
        String localTool = localToolName(toolName);
        JsonNode args = parseArgs(argsJson);
        applyMutation(localTool, args, targetUrl(localTool, args));
        if ("new_page".equals(localTool)) {
            String pageId = pageId(args);
            if (pageId == null || pageId.isBlank()) {
                pageId = extractPageId(result);
            }
            session.recordOpenedTab(pageId);
        }
    }

    public static boolean isBrowserTool(String toolName) {
        return toolName != null && (toolName.startsWith(CHROME_DEVTOOLS_PREFIX_HYPHEN)
                || toolName.startsWith(CHROME_DEVTOOLS_PREFIX_UNDERSCORE)
                || toolName.startsWith(PUPPETEER_PREFIX));
    }

    private static String localToolName(String toolName) {
        if (toolName.startsWith(CHROME_DEVTOOLS_PREFIX_HYPHEN)) {
            return toolName.substring(CHROME_DEVTOOLS_PREFIX_HYPHEN.length());
        }
        if (toolName.startsWith(CHROME_DEVTOOLS_PREFIX_UNDERSCORE)) {
            return toolName.substring(CHROME_DEVTOOLS_PREFIX_UNDERSCORE.length());
        }
        if (toolName.startsWith(PUPPETEER_PREFIX)) {
            return toolName.substring(PUPPETEER_PREFIX.length());
        }
        return toolName;
    }

    private static JsonNode parseArgs(String argsJson) {
        try {
            return MAPPER.readTree(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    private static String targetUrl(String localTool, JsonNode args) {
        if (!"navigate_page".equals(localTool) && !"new_page".equals(localTool) && !"navigate".equals(localTool)) {
            return null;
        }
        if (args.hasNonNull("url")) {
            return args.get("url").asText();
        }
        return null;
    }

    private void applyMutation(String localTool, JsonNode args, String targetUrl) {
        if (targetUrl != null && !targetUrl.isBlank()) {
            session.rememberNavigation(targetUrl);
        }
        if ("close_page".equals(localTool)) {
            String pageId = pageId(args);
            if (pageId != null && !pageId.isBlank()) {
                session.removeOpenedTab(pageId);
            }
        }
    }

    private static String pageId(JsonNode args) {
        if (args.hasNonNull("pageId")) return args.get("pageId").asText();
        if (args.hasNonNull("page_id")) return args.get("page_id").asText();
        if (args.hasNonNull("tabId")) return args.get("tabId").asText();
        return "";
    }

    private static String extractPageId(String raw) {
        if (raw == null || raw.isBlank()) return "";
        Matcher matcher = PAGE_ID_PATTERN.matcher(raw);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }
}
