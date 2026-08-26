package com.xhlcli.app;

import com.xhlcli.llm.CancellationToken;
import com.xhlcli.llm.LlmClient;
import com.xhlcli.llm.LlmErrorType;
import com.xhlcli.llm.LlmException;
import com.xhlcli.model.ChatMessage;
import com.xhlcli.model.ChatResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ChatSession {
    public static final String SYSTEM_PROMPT =
            "You are XhlCLI, a helpful coding assistant. In Phase 01 you have no tools "
                    + "and must not claim to inspect or modify local files.";

    private final LlmClient client;
    private final List<ChatMessage> history = new ArrayList<>();

    public ChatSession(LlmClient client) {
        this.client = Objects.requireNonNull(client, "client");
        clear();
    }

    public synchronized void send(String input, ChatEventSink events, CancellationToken cancellationToken) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        if (input.isBlank()) {
            return;
        }

        ChatMessage userMessage = new ChatMessage(ChatMessage.Role.USER, input);
        List<ChatMessage> request = new ArrayList<>(history);
        request.add(userMessage);
        events.accept(new ChatEvent.Waiting());

        try {
            ChatResponse response = client.stream(
                    List.copyOf(request),
                    delta -> {
                        if (delta != null && !delta.isEmpty()) {
                            events.accept(new ChatEvent.TextDelta(delta));
                        }
                    },
                    cancellationToken);
            history.add(userMessage);
            history.add(new ChatMessage(ChatMessage.Role.ASSISTANT, response.content()));
            events.accept(new ChatEvent.Completed(response.usage()));
        } catch (LlmException failure) {
            if (failure.type() == LlmErrorType.CANCELLED) {
                events.accept(new ChatEvent.Cancelled());
            } else {
                events.accept(new ChatEvent.Failed(
                        failure.type(), failure.getMessage(), failure.partialResponse()));
            }
        }
    }

    public synchronized void clear() {
        history.clear();
        history.add(new ChatMessage(ChatMessage.Role.SYSTEM, SYSTEM_PROMPT));
    }

    public synchronized List<ChatMessage> history() {
        return List.copyOf(history);
    }
}
