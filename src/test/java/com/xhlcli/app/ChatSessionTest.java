package com.xhlcli.app;

import com.xhlcli.llm.CancellationToken;
import com.xhlcli.llm.LlmClient;
import com.xhlcli.llm.LlmErrorType;
import com.xhlcli.llm.LlmException;
import com.xhlcli.llm.StreamListener;
import com.xhlcli.model.ChatMessage;
import com.xhlcli.model.ChatResponse;
import com.xhlcli.model.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatSessionTest {
    private static final ChatMessage SYSTEM_MESSAGE = new ChatMessage(
            ChatMessage.Role.SYSTEM,
            "You are XhlCLI, a helpful coding assistant. In Phase 01 you have no tools "
                    + "and must not claim to inspect or modify local files.");

    @Test
    void sendsFiveTurnsInOrderAndCommitsOnlyCompleteResponses() {
        RecordingClient client = new RecordingClient();
        ChatSession session = new ChatSession(client);

        for (int turn = 1; turn <= 5; turn++) {
            session.send("question " + turn, ignored -> {}, new CancellationToken());
        }

        assertEquals(5, client.requests.size());
        assertEquals(List.of(SYSTEM_MESSAGE, new ChatMessage(ChatMessage.Role.USER, "question 1")),
                client.requests.get(0));
        assertEquals(10, client.requests.get(4).size());
        assertEquals(new ChatMessage(ChatMessage.Role.ASSISTANT, "answer 4"), client.requests.get(4).get(8));
        assertEquals(new ChatMessage(ChatMessage.Role.USER, "question 5"), client.requests.get(4).get(9));
        assertEquals(11, session.history().size());
        assertEquals(new ChatMessage(ChatMessage.Role.ASSISTANT, "answer 5"), session.history().get(10));
    }

    @Test
    void clearRestoresOnlyTheFixedSystemMessageAndHistoryIsImmutable() {
        ChatSession session = new ChatSession(new RecordingClient());
        session.send("hello", ignored -> {}, new CancellationToken());

        session.clear();

        assertEquals(List.of(SYSTEM_MESSAGE), session.history());
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> session.history().add(new ChatMessage(ChatMessage.Role.USER, "mutate")));
    }

    @Test
    void blankInputDoesNotCallTheProvider() {
        RecordingClient client = new RecordingClient();
        ChatSession session = new ChatSession(client);

        session.send("  ", ignored -> {}, new CancellationToken());

        assertEquals(0, client.requests.size());
        assertEquals(List.of(SYSTEM_MESSAGE), session.history());
    }

    @Test
    void failuresAndCancellationRollBackTheTentativeUserTurn() {
        for (LlmException failure : List.of(
                new LlmException(LlmErrorType.AUTHENTICATION, "bad auth", false, false),
                new LlmException(LlmErrorType.CANCELLED, "cancelled", false, false),
                new LlmException(LlmErrorType.INVALID_RESPONSE, "truncated", false, true))) {
            ChatSession session = new ChatSession(new FailingClient(failure));
            List<ChatEvent> events = new ArrayList<>();

            session.send("will fail", events::add, new CancellationToken());

            assertEquals(List.of(SYSTEM_MESSAGE), session.history());
            if (failure.type() == LlmErrorType.CANCELLED) {
                assertEquals(ChatEvent.Cancelled.class, events.get(events.size() - 1).getClass());
            } else {
                ChatEvent.Failed event = (ChatEvent.Failed) events.get(events.size() - 1);
                assertEquals(failure.type(), event.type());
                assertEquals(failure.partialResponse(), event.partial());
            }
        }
    }

    private static final class RecordingClient implements LlmClient {
        private final List<List<ChatMessage>> requests = new ArrayList<>();

        @Override
        public ChatResponse stream(List<ChatMessage> messages, StreamListener listener, CancellationToken token) {
            requests.add(List.copyOf(messages));
            String answer = "answer " + requests.size();
            listener.onTextDelta(answer.substring(0, 3));
            listener.onTextDelta(answer.substring(3));
            return new ChatResponse(answer, new TokenUsage(10, 2, true));
        }
    }

    private record FailingClient(LlmException failure) implements LlmClient {
        @Override
        public ChatResponse stream(List<ChatMessage> messages, StreamListener listener, CancellationToken token)
                throws LlmException {
            if (failure.partialResponse()) {
                listener.onTextDelta("partial");
            }
            throw failure;
        }
    }
}
