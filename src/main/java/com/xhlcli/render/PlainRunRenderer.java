package com.xhlcli.render;

import com.xhlcli.config.ChatConfig;
import com.xhlcli.config.ConfigKey;
import com.xhlcli.config.SecretRedactor;
import com.xhlcli.model.RunEvent;

import java.io.PrintStream;
import java.util.Objects;
import java.util.regex.Pattern;

/** Renders safe, human-readable RunEvent timelines without terminal escape sequences. */
public final class PlainRunRenderer {
    private static final int SUMMARY_LIMIT = 200;
    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[;?0-9]*[ -/]*[@-~]");

    private final PrintStream out;
    private final PrintStream err;
    private final String apiKey;
    private String activeRunId;
    private boolean thinkingShown;
    private boolean assistantPrefixShown;
    private boolean assistantLineOpen;

    public PlainRunRenderer(PrintStream out, PrintStream err, String apiKey) {
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
        this.apiKey = apiKey;
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
            case RunEvent.RunFailed failed -> renderTerminalError("FAILED", failed.reason());
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

    public synchronized void printUnknownCommand(String command) {
        err.println("Unknown command: " + safe(command));
        err.println("Type /help for available commands.");
    }

    private void renderDelta(String text) {
        if (!assistantPrefixShown) {
            out.print("Assistant: ");
            assistantPrefixShown = true;
        }
        out.print(safe(text));
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
        if (assistantLineOpen) {
            err.println();
            assistantLineOpen = false;
        }
        err.println("Run " + status + ": " + safeSummary(reason));
    }

    private void closeAssistantLine() {
        if (assistantLineOpen) {
            out.println();
            assistantLineOpen = false;
        }
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
        }
    }

    private String safeSummary(String value) {
        String safe = safe(value);
        return safe.length() <= SUMMARY_LIMIT ? safe : safe.substring(0, SUMMARY_LIMIT) + "…";
    }

    private String safe(String value) {
        String redacted = SecretRedactor.redact(value == null ? "" : value, apiKey);
        return ANSI.matcher(redacted).replaceAll("");
    }
}
