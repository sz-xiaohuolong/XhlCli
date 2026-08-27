package com.xhlcli.cli;

import com.xhlcli.app.ChatSession;
import com.xhlcli.config.ChatConfig;
import com.xhlcli.config.ConfigKey;
import com.xhlcli.config.ConfigSource;
import com.xhlcli.config.LogLevel;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.llm.LlmClient;
import com.xhlcli.llm.LlmErrorType;
import com.xhlcli.llm.LlmException;
import com.xhlcli.llm.StreamListener;
import com.xhlcli.model.ChatMessage;
import com.xhlcli.model.ChatResponse;
import com.xhlcli.model.TokenUsage;
import com.xhlcli.model.ToolDefinition;
import com.xhlcli.render.PlainChatRenderer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatLoopTest {

    @Test
    void routesCommandsBlankInputAndFiveTurnsWithoutCallingCommandsAsPrompts() {
        ListInput input = new ListInput(List.of(
                "/help", "/config", " ", "/tools",
                "one", "two", "three", "four", "five",
                "/clear", "/exit"));
        RecordingClient client = new RecordingClient();
        Harness harness = new Harness(input, client);

        assertEquals(0, harness.loop.run());

        assertEquals(5, client.requests.size());
        assertTrue(harness.out().contains("/help"));
        assertTrue(harness.out().contains("apiKey=configured (DOT_ENV)"));
        assertFalse(harness.out().contains("test-key"));
        assertTrue(harness.err().contains("Unknown command: /tools"));
        assertTrue(harness.out().contains("Conversation cleared."));
        assertTrue(harness.out().contains("Goodbye."));
        assertEquals(1, harness.session.history().size());
    }

    @Test
    void providerFailureDoesNotStopTheNextTurn() {
        FailsOnceClient client = new FailsOnceClient();
        Harness harness = new Harness(new ListInput(List.of("first", "second", "/exit")), client);

        assertEquals(0, harness.loop.run());

        assertEquals(2, client.calls);
        assertTrue(harness.err().contains("NETWORK"));
        assertTrue(harness.out().contains("Assistant: OK"));
    }

    @Test
    void eofAndIdleInterruptAreNormalInteractiveEvents() {
        Harness eof = new Harness(prompt -> { throw new InputEndOfFileException(); }, new RecordingClient());
        assertEquals(0, eof.loop.run());

        InputReader interruptedThenExit = new InputReader() {
            private boolean first = true;

            @Override
            public String readLine(String prompt) {
                if (first) {
                    first = false;
                    throw new InputInterruptedException();
                }
                return "/exit";
            }
        };
        Harness interrupted = new Harness(interruptedThenExit, new RecordingClient());
        assertFalse(interrupted.loop.cancelActiveResponse());
        assertEquals(0, interrupted.loop.run());
    }

    @Test
    void activeResponseCanBeCancelledAndLoopContinues() throws Exception {
        QueueInput input = new QueueInput();
        CancellableThenSuccessClient client = new CancellableThenSuccessClient();
        Harness harness = new Harness(input, client);
        input.add("please wait");

        CompletableFuture<Integer> run = CompletableFuture.supplyAsync(harness.loop::run);
        assertTrue(client.requestStarted.await(2, TimeUnit.SECONDS));
        assertTrue(harness.loop.cancelActiveResponse());
        assertFalse(harness.loop.cancelActiveResponse());
        input.add("reply only OK");
        input.add("/exit");

        assertEquals(0, run.get(5, TimeUnit.SECONDS));
        assertTrue(client.firstRequestWasCancelled);
        assertTrue(harness.out().contains("Assistant: OK"));
    }

    private static final class Harness {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final ByteArrayOutputStream err = new ByteArrayOutputStream();
        private final ChatSession session;
        private final ChatLoop loop;

        private Harness(InputReader input, LlmClient client) {
            ChatConfig config = config();
            PlainChatRenderer renderer = new PlainChatRenderer(
                    new PrintStream(out, true, StandardCharsets.UTF_8),
                    new PrintStream(err, true, StandardCharsets.UTF_8),
                    config.apiKey());
            session = new ChatSession(client);
            loop = new ChatLoop(input, new ChatCommandParser(), session, renderer, config);
        }

        private String out() {
            return out.toString(StandardCharsets.UTF_8);
        }

        private String err() {
            return err.toString(StandardCharsets.UTF_8);
        }
    }

    private static ChatConfig config() {
        return new ChatConfig(
                "test-key", "test-model", URI.create("https://api.deepseek.com"),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1),
                LogLevel.WARN,
                Map.of(ConfigKey.API_KEY, ConfigSource.DOT_ENV, ConfigKey.MODEL, ConfigSource.DEFAULT));
    }

    private record ListInput(List<String> values) implements InputReader {
        private ListInput {
            values = new ArrayList<>(values);
        }

        @Override
        public String readLine(String prompt) {
            if (values.isEmpty()) {
                throw new InputEndOfFileException();
            }
            return values.remove(0);
        }
    }

    private static final class QueueInput implements InputReader {
        private final BlockingQueue<String> values = new LinkedBlockingQueue<>();

        void add(String value) {
            values.add(value);
        }

        @Override
        public String readLine(String prompt) {
            try {
                return values.take();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new InputInterruptedException();
            }
        }
    }

    private static class RecordingClient implements LlmClient {
        final List<List<ChatMessage>> requests = new ArrayList<>();

        @Override
        public ChatResponse stream(
                List<ChatMessage> messages,
                List<ToolDefinition> tools,
                StreamListener listener,
                CancellationToken token) {
            requests.add(List.copyOf(messages));
            listener.onTextDelta("OK");
            return new ChatResponse("OK", TokenUsage.unknown());
        }
    }

    private static final class FailsOnceClient implements LlmClient {
        private int calls;

        @Override
        public ChatResponse stream(
                List<ChatMessage> messages,
                List<ToolDefinition> tools,
                StreamListener listener,
                CancellationToken token) throws LlmException {
            calls++;
            if (calls == 1) {
                throw new LlmException(LlmErrorType.NETWORK, "offline", true, false);
            }
            listener.onTextDelta("OK");
            return new ChatResponse("OK", TokenUsage.unknown());
        }
    }

    private static final class CancellableThenSuccessClient implements LlmClient {
        private final CountDownLatch requestStarted = new CountDownLatch(1);
        private boolean firstRequestWasCancelled;
        private int calls;

        @Override
        public ChatResponse stream(
                List<ChatMessage> messages,
                List<ToolDefinition> tools,
                StreamListener listener,
                CancellationToken token) throws LlmException {
            calls++;
            if (calls == 1) {
                CountDownLatch cancelled = new CountDownLatch(1);
                token.onCancel(cancelled::countDown);
                requestStarted.countDown();
                try {
                    firstRequestWasCancelled = cancelled.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
                throw new LlmException(LlmErrorType.CANCELLED, "cancelled", false, false);
            }
            listener.onTextDelta("OK");
            return new ChatResponse("OK", TokenUsage.unknown());
        }
    }
}
