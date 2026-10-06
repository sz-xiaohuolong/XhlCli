package com.xhlcli.render.terminal;

import com.xhlcli.config.ChatConfig;
import com.xhlcli.config.ConfigKey;
import com.xhlcli.config.SecretRedactor;
import com.xhlcli.config.StreamingSecretRedactor;
import com.xhlcli.llm.LlmErrorType;
import com.xhlcli.model.RunEvent;
import com.xhlcli.render.TerminalExtSummary;
import com.xhlcli.render.TerminalRenderer;
import com.xhlcli.render.TerminalStatus;
import com.xhlcli.render.TerminalTextSanitizer;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;

import java.io.PrintWriter;
import java.util.Objects;

/**
 * 行内流式终端渲染器。
 * 默认交互终端实现，具备 ANSI 语法色彩、输入行防覆盖保护、底部动态状态栏与输出串行化。
 */
public final class InlineTerminalRenderer implements TerminalRenderer {

    private static final int SUMMARY_LIMIT = 200;

    private final Terminal terminal;
    private final LineReader lineReader;
    private final TerminalStatusBar statusBar;
    private final String apiKey;

    private String activeRunId;
    private boolean thinkingShown;
    private boolean assistantPrefixShown;
    private boolean assistantLineOpen;
    private StreamingSecretRedactor textSecretRedactor;
    private TerminalTextSanitizer textTerminalSanitizer;

    public InlineTerminalRenderer(Terminal terminal, LineReader lineReader, String apiKey) {
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        this.lineReader = lineReader;
        this.statusBar = new TerminalStatusBar(terminal);
        this.apiKey = apiKey;
        resetTextSanitizers();
    }

    public InlineTerminalRenderer(Terminal terminal, String apiKey) {
        this(terminal, null, apiKey);
    }

    public TerminalStatusBar statusBar() {
        return statusBar;
    }

    @Override
    public synchronized void accept(RunEvent event) {
        RunEvent nonNullEvent = Objects.requireNonNull(event, "event");
        resetForRunIfNeeded(nonNullEvent);

        switch (nonNullEvent) {
            case RunEvent.RunStarted ignored -> { }
            case RunEvent.ModelRequestStarted ignored -> renderThinking();
            case RunEvent.TextDelta delta -> renderDelta(delta.text());
            case RunEvent.ModelRequestCompleted ignored -> { }
            case RunEvent.ToolStarted started -> renderToolStarted(started);
            case RunEvent.ToolCompleted completed -> renderToolCompleted(completed);
            case RunEvent.IterationCompleted ignored -> { }
            case RunEvent.RunCompleted completed -> renderCompleted(completed);
            case RunEvent.RunFailed failed -> renderFailure(failed);
            case RunEvent.RunCancelled cancelled -> renderTerminalError("CANCELED", cancelled.reason());
            case RunEvent.RunLimitReached limit -> renderTerminalError("LIMIT_REACHED", limit.reason());
        }
    }

    @Override
    public synchronized void printWelcome(String model) {
        printWelcome(model, "0.13.0", ".", TerminalExtSummary.empty());
    }

    @Override
    public synchronized void printWelcome(String model, String version, String workspace, TerminalExtSummary summary) {
        println("\u001B[1;36m╭────────────────────────────────────────────────────────╮\u001B[0m");
        println(String.format("\u001B[1;36m│  XhlCLI v%-10s \u001B[0;33m(AI Model: %-25s)\u001B[1;36m│\u001B[0m", version, safe(model)));
        println(String.format("\u001B[1;36m│  Workspace: %-42s │\u001B[0m", TerminalWidthCalculator.truncateToWidth(safe(workspace), 42, "…")));
        if (summary != null && (summary.mcpServerCount() > 0 || summary.skillCount() > 0 || summary.browserConnected())) {
            String extDesc = String.format("MCP: %d | Skills: %d | Browser: %s",
                    summary.mcpServerCount(), summary.skillCount(), summary.browserConnected() ? "On" : "Off");
            println(String.format("\u001B[1;36m│  Extensions: %-41s │\u001B[0m", extDesc));
        }
        println("\u001B[1;36m╰────────────────────────────────────────────────────────╯\u001B[0m");
        println("\u001B[2m💡 入门提示:\u001B[0m");
        println("  • 直接输入自然语言描述任务，Agent 将规划并调用工具执行");
        println("  • 输入 \u001B[33m/help\u001B[0m 查看所有可用命令与快捷操作");
        println("  • 输入 \u001B[33m/model\u001B[0m 切换不同的大语言模型或提供商");
    }

    @Override
    public synchronized void printHelp() {
        println("\u001B[1m常用命令列表:\u001B[0m");
        println("  \u001B[33m/help\u001B[0m                  显示本帮助菜单");
        println("  \u001B[33m/config\u001B[0m                查看非敏感配置详情");
        println("  \u001B[33m/clear\u001B[0m                 清空当前会话历史");
        println("  \u001B[33m/history [list|clear]\u001B[0m  查看或清空本地输入历史");
        println("  \u001B[33m/model [list|use|status]\u001B[0m 管理与切换 AI 模型提供商");
        println("  \u001B[33m/mcp [list|status|...]\u001B[0m  MCP 服务器与外部工具管理");
        println("  \u001B[33m/skill [list|show|...]\u001B[0m 技能库管理与热重载");
        println("  \u001B[33m/browser [status|...]\u001B[0m  浏览器 CDP 连接与调试");
        println("  \u001B[33m/plan [goal]\u001B[0m           启动 Plan-and-Execute 规划任务");
        println("  \u001B[33m/team <goal>\u001B[0m           启动 Multi-Agent 团队协同");
        println("  \u001B[33m/search <query>\u001B[0m        混合语义代码检索");
        println("  \u001B[33m/search-text <pat>\u001B[0m     精准全文/正则搜索");
        println("  \u001B[33m/index [status|clean]\u001B[0m  代码库 AST 语义索引运维");
        println("  \u001B[33m/prompt [show|export]\u001B[0m  查看或脱敏导出分层 System Prompt");
        println("  \u001B[33m/exit\u001B[0m                  退出终端");
        println("  \u001B[2m快捷键: Ctrl+C 取消当前执行 | Tab 自动补全\u001B[0m");
    }

    @Override
    public synchronized void printConfig(ChatConfig config) {
        String keyState = config.hasApiKey() ? "\u001B[32mconfigured (" + config.source(ConfigKey.API_KEY) + ")\u001B[0m" : "\u001B[31mmissing\u001B[0m";
        println("apiKey=" + keyState);
        println(String.format("model=\u001B[33m%s\u001B[0m (%s)", safe(config.model()), config.source(ConfigKey.MODEL)));
        println("baseUrl=" + safe(config.baseUrl().toString()));
        println("connectTimeout=" + config.connectTimeout().toSeconds() + "s");
        println("readTimeout=" + config.readTimeout().toSeconds() + "s");
        println("requestTimeout=" + config.requestTimeout().toSeconds() + "s");
        println("agentMaxIterations=" + config.agentSettings().maxIterations() + " (" + config.source(ConfigKey.AGENT_MAX_ITERATIONS) + ")");
        println("agentTimeout=" + config.agentSettings().timeout().toSeconds() + "s (" + config.source(ConfigKey.AGENT_TIMEOUT) + ")");
        println("maxConcurrency=" + config.agentSettings().maxConcurrency() + " (" + config.source(ConfigKey.MAX_CONCURRENCY) + ")");
        println("toolTimeout=" + config.agentSettings().toolTimeout().toSeconds() + "s (" + config.source(ConfigKey.TOOL_TIMEOUT) + ")");
        println("logLevel=" + config.logLevel());
    }

    @Override
    public synchronized void printMessage(String message) {
        println(message);
    }

    @Override
    public synchronized void printErrorMessage(String message) {
        println("\u001B[31m✖ 错误: " + safe(message) + "\u001B[0m");
    }

    @Override
    public synchronized void printUnknownCommand(String command) {
        println("\u001B[31m✖ 未知命令: " + safe(command) + "\u001B[0m");
        println("输入 \u001B[33m/help\u001B[0m 查看所有可用命令。");
    }

    @Override
    public synchronized void printCleared() {
        println("\u001B[32m✔ 会话历史已清空。\u001B[0m");
    }

    @Override
    public synchronized void printGoodbye() {
        println("\u001B[36m再见！感谢使用 XhlCLI。\u001B[0m");
    }

    @Override
    public synchronized void updateStatus(TerminalStatus status) {
        if (statusBar != null) {
            statusBar.update(status);
        }
    }

    @Override
    public synchronized void close() {
        closeAssistantLine();
        if (statusBar != null) {
            statusBar.hide();
        }
    }

    private void renderDelta(String text) {
        writeAssistantText(textTerminalSanitizer.accept(textSecretRedactor.accept(text)));
    }

    private void writeAssistantText(String text) {
        if (text.isEmpty()) {
            return;
        }
        PrintWriter writer = terminal.writer();
        if (!assistantPrefixShown) {
            writer.print("\u001B[1;34mAssistant:\u001B[0m ");
            assistantPrefixShown = true;
        }
        writer.print(text);
        assistantLineOpen = true;
        writer.flush();
    }

    private void renderToolStarted(RunEvent.ToolStarted event) {
        closeAssistantLine();
        println(String.format("\u001B[33m⚙️  Tool\u001B[0m \u001B[1m%s\u001B[0m: %s",
                safe(event.toolName()), safeSummary(event.argumentsSummary())));
    }

    private void renderToolCompleted(RunEvent.ToolCompleted event) {
        closeAssistantLine();
        String statusColor = (event.status() == com.xhlcli.model.ToolResultStatus.SUCCESS) ? "\u001B[32m" : "\u001B[31m";
        println(String.format("%s✓ Tool\u001B[0m \u001B[1m%s\u001B[0m: %s%s\u001B[0m (%dms) %s",
                statusColor, safe(event.toolName()), statusColor, event.status(),
                event.elapsedMillis(), safeSummary(event.summary())));
    }

    private void renderCompleted(RunEvent.RunCompleted event) {
        closeAssistantLine();
        String tokenInfo = event.usage().known()
                ? String.format("[tokens: in=%d, out=%d]", event.usage().inputTokens(), event.usage().outputTokens())
                : "[tokens: unknown]";
        println(String.format("\u001B[32m✔ 任务完成\u001B[0m \u001B[2m%s\u001B[0m", tokenInfo));
    }

    private void renderTerminalError(String status, String reason) {
        closeAssistantLine();
        println(String.format("\u001B[31m✖ 执行异常 [%s]: %s\u001B[0m", status, safeSummary(reason)));
    }

    private void renderFailure(RunEvent.RunFailed failure) {
        closeAssistantLine();
        if (failure.errorType() == null) {
            renderTerminalError("FAILED", failure.reason());
            return;
        }
        String partial = failure.partialResponse() ? " (回复不完整)" : "";
        println(String.format("\u001B[31m✖ [%s]%s %s\u001B[0m", failure.errorType(), partial, safe(failure.safeMessage())));
        println("\u001B[33m💡 建议: " + suggestion(failure.errorType()) + "\u001B[0m");
    }

    private String suggestion(LlmErrorType type) {
        return switch (type) {
            case MISSING_CONFIGURATION -> "请在项目 .env 文件中配置 API_KEY。";
            case AUTHENTICATION -> "请检查 API_KEY 是否有效或已欠费。";
            case RATE_LIMIT -> "请求触发频率限制，请稍候片刻重试。";
            case NETWORK -> "网络连接异常，请检查网络或代理设置。";
            case SERVER -> "模型服务商暂时不可用，请稍后重试或切换模型。";
            case INVALID_RESPONSE, EMPTY_RESPONSE -> "模型返回格式异常，可尝试重试或使用 DEBUG 日志排查。";
            case TIMEOUT -> "请求超时，建议延长超时时间或检查网络质量。";
            case CANCELLED -> "操作已取消。";
            case INVALID_CONFIGURATION -> "配置项不合法，请检查模型名称与 URL 设置。";
        };
    }

    private void closeAssistantLine() {
        String secretTail = textSecretRedactor.finish();
        writeAssistantText(textTerminalSanitizer.accept(secretTail) + textTerminalSanitizer.finish());
        if (assistantLineOpen) {
            terminal.writer().println();
            terminal.writer().flush();
            assistantLineOpen = false;
        }
        resetTextSanitizers();
    }

    private void renderThinking() {
        if (!thinkingShown) {
            println("\u001B[2;36m⏳ Thinking...\u001B[0m");
            thinkingShown = true;
        }
    }

    private void resetForRunIfNeeded(RunEvent event) {
        String runId = event.metadata().runId();
        if (event instanceof RunEvent.RunStarted || !runId.equals(activeRunId)) {
            closeAssistantLine();
            activeRunId = runId;
            thinkingShown = false;
            assistantPrefixShown = false;
            assistantLineOpen = false;
            resetTextSanitizers();
        }
    }

    private void println(String line) {
        if (lineReader != null && lineReader.isReading()) {
            lineReader.printAbove(line);
        } else {
            terminal.writer().println(line);
            terminal.writer().flush();
        }
    }

    private String safeSummary(String value) {
        String safe = safe(value);
        return safe.length() <= SUMMARY_LIMIT ? safe : safe.substring(0, SUMMARY_LIMIT) + "…";
    }

    private String safe(String value) {
        String redacted = SecretRedactor.redact(value == null ? "" : value, apiKey);
        return TerminalTextSanitizer.sanitize(redacted);
    }

    private void resetTextSanitizers() {
        textSecretRedactor = new StreamingSecretRedactor(apiKey);
        textTerminalSanitizer = new TerminalTextSanitizer();
    }
}
