package com.xhlcli.render;

import com.xhlcli.config.ChatConfig;
import com.xhlcli.config.ConfigKey;
import com.xhlcli.config.SecretRedactor;
import com.xhlcli.config.StreamingSecretRedactor;
import com.xhlcli.llm.LlmErrorType;
import com.xhlcli.model.RunEvent;

import java.io.PrintStream;
import java.util.Objects;

/** Renders safe, human-readable RunEvent timelines without terminal escape sequences. */
public final class PlainRunRenderer {
    private static final int SUMMARY_LIMIT = 200;

    private final PrintStream out;
    private final PrintStream err;
    private final String apiKey;
    private String activeRunId;
    private boolean thinkingShown;
    private boolean assistantPrefixShown;
    private boolean assistantLineOpen;
    private StreamingSecretRedactor textSecretRedactor;
    private TerminalTextSanitizer textTerminalSanitizer;

    public PlainRunRenderer(PrintStream out, PrintStream err, String apiKey) {
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
        this.apiKey = apiKey;
        resetTextSanitizers();
    }

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

    public synchronized void printWelcome(String model) {
        out.printf("XhlCLI Phase 02 — ReAct demo agent (%s)%n", safe(model));
        out.println("Type /help for commands.");
    }

    public synchronized void printHelp() {
        out.println("Commands:");
        out.println("  /help     Show this help");
        out.println("  /config   Show non-secret configuration");
        out.println("  /clear    Clear conversation history");
        out.println("  /index [status|clean]  Build, check, or clean codebase index");
        out.println("  /search <query>        Semantic & hybrid code search");
        out.println("  /search-text <pattern> Exact regex/text code search");
        out.println("  /plan [goal]           Plan-and-Execute structured task");
        out.println("  /exit     Exit XhlCLI");
        out.println("  Ctrl+C    Cancel the active run");
    }

    public synchronized void printConfig(ChatConfig config) {
        String keyState = config.hasApiKey() ? "configured (" + config.source(ConfigKey.API_KEY) + ")" : "missing";
        out.println("apiKey=" + keyState);
        out.printf("model=%s (%s)%n", safe(config.model()), config.source(ConfigKey.MODEL));
        out.println("baseUrl=" + safe(config.baseUrl().toString()));
        out.println("connectTimeout=" + config.connectTimeout().toSeconds() + "s");
        out.println("readTimeout=" + config.readTimeout().toSeconds() + "s");
        out.println("requestTimeout=" + config.requestTimeout().toSeconds() + "s");
        out.println("agentMaxIterations=" + config.agentSettings().maxIterations() + " (" + config.source(ConfigKey.AGENT_MAX_ITERATIONS) + ")");
        out.println("agentTimeout=" + config.agentSettings().timeout().toSeconds() + "s (" + config.source(ConfigKey.AGENT_TIMEOUT) + ")");
        out.println("logLevel=" + config.logLevel());
    }

    public synchronized void printCleared() { out.println("Conversation cleared."); }
    public synchronized void printGoodbye() { out.println("Goodbye."); }
    public synchronized void printMessage(String message) { out.println(message); }

    public synchronized void printUnknownCommand(String command) {
        err.println("Unknown command: " + safe(command));
        err.println("Type /help for available commands.");
    }

    private void renderDelta(String text) {
        writeAssistantText(textTerminalSanitizer.accept(textSecretRedactor.accept(text)));
    }

    private void writeAssistantText(String text) {
        if (text.isEmpty()) {
            return;
        }
        if (!assistantPrefixShown) {
            out.print("Assistant: ");
            assistantPrefixShown = true;
        }
        out.print(text);
        assistantLineOpen = true;
        out.flush();
    }

    private void renderToolStarted(RunEvent.ToolStarted event) {
        closeAssistantLine();
        out.println("Tool " + safe(event.toolName()) + ": " + safeSummary(event.argumentsSummary()));
    }

    private void renderToolCompleted(RunEvent.ToolCompleted event) {
        closeAssistantLine();
        out.println("Tool " + safe(event.toolName()) + ": " + event.status() + " (" + event.elapsedMillis()
                + "ms) " + safeSummary(event.summary()));
    }

    private void renderCompleted(RunEvent.RunCompleted event) {
        closeAssistantLine();
        if (event.usage().known()) {
            out.printf("[tokens: input=%d, output=%d]%n", event.usage().inputTokens(), event.usage().outputTokens());
        } else {
            out.println("[tokens: unknown]");
        }
        out.println("Run completed.");
    }

    private void renderTerminalError(String status, String reason) {
        closeAssistantLine();
        err.println("Run " + status + ": " + safeSummary(reason));
    }

    private void renderFailure(RunEvent.RunFailed failure) {
        if (failure.errorType() == null) {
            renderTerminalError("FAILED", failure.reason());
            return;
        }
        closeAssistantLine();
        String partial = failure.partialResponse() ? " (response incomplete)" : "";
        err.printf("[%s]%s %s%n", failure.errorType(), partial, safe(failure.safeMessage()));
        err.println("Suggestion: " + suggestion(failure.errorType()));
    }

    private String suggestion(LlmErrorType type) {
        return switch (type) {
            case MISSING_CONFIGURATION -> "Set DEEPSEEK_API_KEY in the project .env file.";
            case AUTHENTICATION -> "Check DEEPSEEK_API_KEY and its account permissions.";
            case RATE_LIMIT -> "Wait briefly, then try again.";
            case NETWORK -> "Check your network connection and DeepSeek endpoint.";
            case SERVER -> "The provider is unavailable; try again later.";
            case INVALID_RESPONSE, EMPTY_RESPONSE -> "Retry the request; use DEBUG logs if the problem persists.";
            case TIMEOUT -> "Increase the timeout or retry on a stable connection.";
            case CANCELLED -> "Submit a new prompt when ready.";
            case INVALID_CONFIGURATION -> "Check the configured model, URL, and timeout values.";
        };
    }

    private void closeAssistantLine() {
        String secretTail = textSecretRedactor.finish();
        writeAssistantText(textTerminalSanitizer.accept(secretTail) + textTerminalSanitizer.finish());
        if (assistantLineOpen) {
            out.println();
            assistantLineOpen = false;
        }
        resetTextSanitizers();
    }

    private void renderThinking() {
        if (!thinkingShown) {
            out.println("Thinking...");
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
