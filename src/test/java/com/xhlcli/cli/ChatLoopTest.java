package com.xhlcli.cli;

import com.xhlcli.agent.AgentRunner;
import com.xhlcli.config.AgentSettings;
import com.xhlcli.config.ChatConfig;
import com.xhlcli.config.ConfigKey;
import com.xhlcli.config.ConfigSource;
import com.xhlcli.config.LogLevel;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.model.ChatMessage;
import com.xhlcli.model.RunEvent;
import com.xhlcli.model.RunEventSink;
import com.xhlcli.model.RunResult;
import com.xhlcli.model.RunStatus;
import com.xhlcli.model.TokenUsage;
import com.xhlcli.render.PlainRunRenderer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
        FakeAgent agent = new FakeAgent();
        Harness harness = new Harness(new ListInput(List.of(
                "/help", "/config", " ", "/tools", "one", "two", "three", "four", "five", "/clear", "/exit")), agent);

        assertEquals(0, harness.loop.run());

        assertEquals(List.of("one", "two", "three", "four", "five"), agent.inputs);
        assertEquals(1, agent.clearCalls);
        assertTrue(harness.out().contains("agentMaxIterations=10"));
        assertFalse(harness.out().contains("test-key"));
        assertTrue(harness.err().contains("Unknown command: /tools"));
        assertTrue(harness.out().contains("Conversation cleared."));
    }

    @Test
    void routesIndexAndSearchCommands() {
        System.setProperty("xhlcli.rag.dir", "/tmp/xhlcli-test-loop-rag");
        System.setProperty("xhlcli.embedding.provider", "fake");
        FakeAgent agent = new FakeAgent();
        Harness harness = new Harness(new ListInput(List.of(
                "/index status",
                "/search query",
                "/exit")), agent);

        assertEquals(0, harness.loop.run());
        assertTrue(harness.out().contains("索引状态") || harness.out().contains("已索引"));
        assertTrue(harness.out().contains("尚未索引") || harness.out().contains("检索") || harness.out().contains("未找到"));
    }

    @Test
    void eofAndIdleInterruptAreNormalInteractiveEvents() {
        Harness eof = new Harness(prompt -> { throw new InputEndOfFileException(); }, new FakeAgent());
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
        Harness interrupted = new Harness(interruptedThenExit, new FakeAgent());
        assertFalse(interrupted.loop.cancelActiveResponse());
        assertEquals(0, interrupted.loop.run());
    }

    @Test
    void failedRunDoesNotStopTheNextTurn() {
        FailingThenSuccessfulAgent agent = new FailingThenSuccessfulAgent();
        Harness harness = new Harness(new ListInput(List.of("first", "second", "/exit")), agent);

        assertEquals(0, harness.loop.run());

        assertEquals(2, agent.calls);
        assertTrue(harness.err().contains("FAILED"));
        assertTrue(harness.out().contains("Assistant: OK"));
    }

    @Test
    void activeResponseCancelsTheSameUserTokenAndLoopContinues() throws Exception {
        QueueInput input = new QueueInput();
        BlockingAgent agent = new BlockingAgent();
        Harness harness = new Harness(input, agent);
        input.add("please wait");

        CompletableFuture<Integer> run = CompletableFuture.supplyAsync(harness.loop::run);
        assertTrue(agent.started.await(2, TimeUnit.SECONDS));
        assertTrue(harness.loop.cancelActiveResponse());
        assertFalse(harness.loop.cancelActiveResponse());
        input.add("reply only OK");
        input.add("/exit");

        assertEquals(0, run.get(5, TimeUnit.SECONDS));
        assertTrue(agent.cancelled);
        assertTrue(harness.out().contains("Assistant: OK"));
    }

    @Test
    void searchTextCommandInvokesGrepToolDirectly() throws Exception {
        FakeAgent agent = new FakeAgent();
        Harness harness = new Harness(new ListInput(List.of("/search-text class EchoTool", "/search-text", "/exit")), agent);
        java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath().normalize();
        com.xhlcli.tool.local.WorkspacePathResolver resolver = new com.xhlcli.tool.local.WorkspacePathResolver(root);
        com.xhlcli.tool.local.search.GrepCodeTool grepTool = new com.xhlcli.tool.local.search.GrepCodeTool(resolver);
        harness.loop.setGrepCodeTool(grepTool);

        assertEquals(0, harness.loop.run());
        assertTrue(harness.out().contains("EchoTool.java:"));
        assertTrue(harness.out().contains("用法: /search-text"));
    }

    private static final class Harness {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final ByteArrayOutputStream err = new ByteArrayOutputStream();
        private final ChatLoop loop;

        private Harness(InputReader input, AgentRunner agent) {
            ChatConfig config = config();
            PlainRunRenderer renderer = new PlainRunRenderer(
                    new PrintStream(out, true, StandardCharsets.UTF_8),
                    new PrintStream(err, true, StandardCharsets.UTF_8), config.apiKey());
            loop = new ChatLoop(input, new ChatCommandParser(), agent, renderer, config);
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
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), LogLevel.WARN,
                new AgentSettings(10, Duration.ofSeconds(600)),
                Map.of(ConfigKey.API_KEY, ConfigSource.DOT_ENV, ConfigKey.MODEL, ConfigSource.DEFAULT));
    }

    private static class FakeAgent implements AgentRunner {
        private final List<String> inputs = new ArrayList<>();
        private int clearCalls;

        @Override
        public RunResult run(String input, RunEventSink events, CancellationToken token) {
            inputs.add(input);
            events.accept(new RunEvent.ModelRequestStarted(metadata(1)));
            events.accept(new RunEvent.TextDelta(metadata(2), "OK"));
            events.accept(new RunEvent.RunCompleted(metadata(3), "OK", TokenUsage.unknown()));
            return new RunResult("run", RunStatus.COMPLETED, "OK", "", 0, TokenUsage.unknown());
        }

        @Override
        public void clearHistory() {
            clearCalls++;
        }

        @Override
        public List<ChatMessage> history() {
            return List.of(ChatMessage.system("test"));
        }
    }

    private static final class BlockingAgent extends FakeAgent {
        private final CountDownLatch started = new CountDownLatch(1);
        private boolean cancelled;

        @Override
        public RunResult run(String input, RunEventSink events, CancellationToken token) {
            if ("please wait".equals(input)) {
                started.countDown();
                token.onCancel(() -> cancelled = true);
                while (!token.isCancelled()) {
                    Thread.onSpinWait();
                }
                events.accept(new RunEvent.RunCancelled(metadata(1), "USER_CANCELED"));
                return new RunResult("run", RunStatus.CANCELED, "", "USER_CANCELED", 0, TokenUsage.unknown());
            }
            return super.run(input, events, token);
        }
    }

    private static final class FailingThenSuccessfulAgent extends FakeAgent {
        private int calls;

        @Override
        public RunResult run(String input, RunEventSink events, CancellationToken token) {
            if (++calls == 1) {
                events.accept(new RunEvent.RunFailed(metadata(1), "NETWORK"));
                return new RunResult("run", RunStatus.FAILED, "", "NETWORK", 0, TokenUsage.unknown());
            }
            return super.run(input, events, token);
        }
    }

    private static RunEvent.Metadata metadata(long sequence) {
        return new RunEvent.Metadata("run", sequence, Instant.EPOCH, 0);
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

        private void add(String value) {
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
}
