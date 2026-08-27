package com.xhlcli.render;

import com.xhlcli.app.ChatEvent;
import com.xhlcli.config.ChatConfig;
import com.xhlcli.config.ConfigKey;
import com.xhlcli.config.SecretRedactor;
import com.xhlcli.llm.LlmErrorType;

import java.io.PrintStream;
import java.util.Objects;

public final class PlainChatRenderer {
    private final PrintStream out;
    private final PrintStream err;
    private final String apiKey;
    private boolean assistantStarted;

    public PlainChatRenderer(PrintStream out, PrintStream err, String apiKey) {
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
        this.apiKey = apiKey;
    }

    public synchronized void accept(ChatEvent event) {
        switch (Objects.requireNonNull(event, "event")) {
            case ChatEvent.Waiting ignored -> out.println("Thinking...");
            case ChatEvent.TextDelta delta -> renderDelta(delta.text());
            case ChatEvent.Completed completed -> renderCompleted(completed);
            case ChatEvent.Failed failed -> renderFailure(failed);
            case ChatEvent.Cancelled ignored -> renderCancelled();
        }
    }

    public synchronized void printWelcome(String model) {
        out.printf("XhlCLI Phase 01 — DeepSeek streaming chat (%s)%n", redact(model));
        out.println("Type /help for commands.");
    }

    public synchronized void printHelp() {
        out.println("Commands:");
        out.println("  /help     Show this help");
        out.println("  /config   Show non-secret configuration");
        out.println("  /clear    Clear conversation history");
        out.println("  /exit     Exit XhlCLI");
        out.println("  Ctrl+C    Cancel the active response");
    }

    public synchronized void printConfig(ChatConfig config) {
        String keyState = config.hasApiKey()
                ? "configured (" + config.source(ConfigKey.API_KEY) + ")"
                : "missing";
        out.println("apiKey=" + keyState);
        out.printf("model=%s (%s)%n", redact(config.model()), config.source(ConfigKey.MODEL));
        out.println("baseUrl=" + config.baseUrl());
        out.println("connectTimeout=" + config.connectTimeout().toSeconds() + "s");
        out.println("readTimeout=" + config.readTimeout().toSeconds() + "s");
        out.println("requestTimeout=" + config.requestTimeout().toSeconds() + "s");
        out.println("logLevel=" + config.logLevel());
    }

    public synchronized void printCleared() {
        out.println("Conversation cleared.");
    }

    public synchronized void printGoodbye() {
        out.println("Goodbye.");
    }

    public synchronized void printUnknownCommand(String command) {
        err.println("Unknown command: " + redact(command));
        err.println("Type /help for available commands.");
    }

    private void renderDelta(String text) {
        if (!assistantStarted) {
            out.print("Assistant: ");
            assistantStarted = true;
        }
        out.print(redact(text));
        out.flush();
    }

    private void renderCompleted(ChatEvent.Completed completed) {
        if (assistantStarted) {
            out.println();
        }
        if (completed.usage().known()) {
            out.printf("[tokens: input=%d, output=%d]%n",
                    completed.usage().inputTokens(), completed.usage().outputTokens());
        } else {
            out.println("[tokens: unknown]");
        }
        assistantStarted = false;
    }

    private void renderFailure(ChatEvent.Failed failed) {
        if (assistantStarted) {
            err.println();
        }
        String partial = failed.partial() ? " (response incomplete)" : "";
        err.printf("[%s]%s %s%n", failed.type(), partial, redact(failed.message()));
        err.println("Suggestion: " + suggestion(failed.type()));
        assistantStarted = false;
    }

    private void renderCancelled() {
        if (assistantStarted) {
            err.println();
        }
        err.println("Cancelled.");
        assistantStarted = false;
    }

    private String suggestion(LlmErrorType type) {
        return switch (type) {
            case MISSING_CONFIGURATION -> "Set DEEPSEEK_API_KEY in the project .env file.";
            case AUTHENTICATION -> "Check DEEPSEEK_API_KEY and its account permissions.";
            case RATE_LIMIT -> "Wait briefly, then try again.";
            case NETWORK -> "Check your network connection and DeepSeek endpoint.";
            case SERVER -> "The provider is unavailable; try again later.";
            case INVALID_RESPONSE -> "Retry the request; use DEBUG logs if the problem persists.";
            case EMPTY_RESPONSE -> "Retry the request; use DEBUG logs if the problem persists.";
            case TIMEOUT -> "Increase the timeout or retry on a stable connection.";
            case CANCELLED -> "Submit a new prompt when ready.";
            case INVALID_CONFIGURATION -> "Check the configured model, URL, and timeout values.";
        };
    }

    private String redact(String text) {
        return SecretRedactor.redact(text, apiKey);
    }
}
