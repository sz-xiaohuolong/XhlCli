package com.xhlcli.render;

import com.xhlcli.app.ChatEvent;
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
            case TIMEOUT -> "Increase the timeout or retry on a stable connection.";
            case CANCELLED -> "Submit a new prompt when ready.";
            case INVALID_CONFIGURATION -> "Check the configured model, URL, and timeout values.";
        };
    }

    private String redact(String text) {
        return SecretRedactor.redact(text, apiKey);
    }
}
