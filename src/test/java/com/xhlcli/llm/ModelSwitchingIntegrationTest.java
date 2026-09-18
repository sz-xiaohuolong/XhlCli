package com.xhlcli.llm;

import com.xhlcli.agent.AgentRunner;
import com.xhlcli.agent.PlanExecuteAgent;
import com.xhlcli.agent.ReactAgent;
import com.xhlcli.agent.RunLimits;
import com.xhlcli.agent.TimeoutScheduler;
import com.xhlcli.cli.ChatCommandParser;
import com.xhlcli.cli.ChatLoop;
import com.xhlcli.cli.InputEndOfFileException;
import com.xhlcli.cli.InputReader;
import com.xhlcli.config.AgentSettings;
import com.xhlcli.config.ChatConfig;
import com.xhlcli.config.ConfigKey;
import com.xhlcli.config.ConfigSource;
import com.xhlcli.config.LogLevel;
import com.xhlcli.context.ContextAssembler;
import com.xhlcli.context.TokenBudget;
import com.xhlcli.model.*;
import com.xhlcli.render.PlainRunRenderer;
import com.xhlcli.team.TeamOrchestrator;
import com.xhlcli.tool.ToolExecutor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class ModelSwitchingIntegrationTest {

    private static class StubLlmClient implements LlmClient {
        private final String provider;
        private final String model;
        private final ModelCapabilities capabilities;
        final AtomicBoolean streamCalled = new AtomicBoolean(false);

        StubLlmClient(String provider, String model, ModelCapabilities capabilities) {
            this.provider = provider;
            this.model = model;
            this.capabilities = capabilities;
        }

        @Override
        public String providerName() { return provider; }

        @Override
        public String modelName() { return model; }

        @Override
        public ModelCapabilities capabilities() { return capabilities; }

        @Override
        public ChatResponse stream(List<ChatMessage> messages, List<ToolDefinition> tools, StreamListener listener, CancellationToken cancellationToken) {
            streamCalled.set(true);
            return new ChatResponse("stub answer", List.of(), new TokenUsage(10, 5, true));
        }
    }

    private static class StubAgent implements AgentRunner {
        @Override
        public RunResult run(String input, RunEventSink events, CancellationToken token) {
            return new RunResult("r1", RunStatus.COMPLETED, "ok", "", 0, TokenUsage.unknown());
        }

        @Override
        public void clearHistory() {}

        @Override
        public List<ChatMessage> history() {
            return List.of();
        }
    }

    private static class ListInputReader implements InputReader {
        private final List<String> lines;
        private int index = 0;

        ListInputReader(List<String> lines) {
            this.lines = lines;
        }

        @Override
        public String readLine(String prompt) {
            if (index < lines.size()) {
                return lines.get(index++);
            }
            throw new InputEndOfFileException();
        }
    }

    @Test
    void modelCommandsListStatusAndUse() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
        PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8);

        ChatConfig config = new ChatConfig(
                "key", "deepseek-chat", URI.create("https://api.deepseek.com"),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1),
                LogLevel.INFO, new AgentSettings(10, Duration.ofSeconds(600)),
                Map.of(ConfigKey.API_KEY, ConfigSource.DOT_ENV, ConfigKey.MODEL, ConfigSource.DEFAULT)
        );

        PlainRunRenderer renderer = new PlainRunRenderer(outStream, errStream, config.apiKey());
        ListInputReader inputReader = new ListInputReader(List.of(
                "/model list",
                "/model status",
                "/model use ollama",
                "/model status",
                "/exit"
        ));

        StubLlmClient initialClient = new StubLlmClient("deepseek", "deepseek-chat", ModelCapabilities.deepseekDefault());
        TokenBudget budget = new TokenBudget(initialClient.capabilities().maxContextWindow());
        ContextAssembler contextAssembler = new ContextAssembler(budget);

        ChatLoop loop = new ChatLoop(inputReader, new ChatCommandParser(), new StubAgent(), renderer, config);

        Map<String, String> env = Map.of("DEEPSEEK_API_KEY", "sk-test");
        LlmProviderRegistry registry = new LlmProviderRegistry(env);

        loop.setLlmClient(initialClient);
        loop.setProviderRegistry(registry);
        loop.setContextAssembler(contextAssembler);

        int exitCode = loop.run();
        assertEquals(0, exitCode);

        String consoleOutput = out.toString(StandardCharsets.UTF_8);

        // Verify /model list
        assertTrue(consoleOutput.contains("可用模型与 Provider 矩阵"));
        assertTrue(consoleOutput.contains("deepseek:deepseek-chat"));
        assertTrue(consoleOutput.contains("ollama:qwen2.5-coder"));

        // Verify /model status before switch
        assertTrue(consoleOutput.contains("Provider:         deepseek"));
        assertTrue(consoleOutput.contains("Model:            deepseek-chat"));

        // Verify /model use ollama switched model & budget
        assertTrue(consoleOutput.contains("已成功切换至模型 [qwen2.5-coder]"));
        assertEquals("ollama", loop.getLlmClient().providerName());
        assertEquals("qwen2.5-coder", loop.getLlmClient().modelName());
        assertEquals(32_768, budget.getContextWindow());
    }

    @Test
    void toolCapabilityGuardPreventsExecutionWhenToolsUnsupported() {
        StubLlmClient textOnlyClient = new StubLlmClient("text-model", "text-only-v1", ModelCapabilities.textOnly(8000));

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        ToolDefinition toolDef = new ToolDefinition("my_tool", "desc", mapper.createObjectNode(), ToolMetadata.conservative());
        ToolExecutor mockExecutor = (call, token) -> new ToolResult(
                call.id(), call.name(), ToolResultStatus.SUCCESS, "done", mapper.createObjectNode(), 10, false, 4, ""
        );

        TimeoutScheduler timeoutScheduler = new TimeoutScheduler() {
            @Override
            public Registration schedule(Duration duration, Runnable task) {
                return () -> {};
            }
            @Override
            public void close() {}
        };

        // 1. ReactAgent Guard
        ReactAgent reactAgent = new ReactAgent(
                ChatMessage.system("sys"),
                textOnlyClient,
                mockExecutor,
                List.of(toolDef),
                new RunLimits(5, Duration.ofSeconds(10)),
                timeoutScheduler,
                mapper,
                Clock.systemUTC(),
                () -> "test-run",
                s -> s,
                () -> new com.xhlcli.config.StreamingSecretRedactor(null)
        );

        List<RunEvent> events = new ArrayList<>();
        RunResult result = reactAgent.run("Do some work", events::add, new CancellationToken());

        assertEquals(RunStatus.FAILED, result.status());
        assertEquals("MODEL_UNSUPPORTED_TOOLS", result.reason());
        assertFalse(textOnlyClient.streamCalled.get(), "LLM should not be called when tools are unsupported!");
        assertTrue(result.finalAnswer().contains("不支持工具调用"));

        // 2. PlanExecuteAgent Guard
        PlanExecuteAgent planAgent = new PlanExecuteAgent(
                textOnlyClient,
                mockExecutor,
                List.of(toolDef),
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute()
        );

        RunResult planResult = planAgent.run("Plan my work", event -> {}, new CancellationToken());
        assertEquals(RunStatus.FAILED, planResult.status());
        assertEquals("MODEL_UNSUPPORTED_TOOLS", planResult.reason());

        // 3. TeamOrchestrator Guard
        TeamOrchestrator teamOrchestrator = new TeamOrchestrator(
                textOnlyClient,
                mockExecutor,
                List.of(toolDef)
        );

        RunResult teamResult = teamOrchestrator.run("Team collaborative work", event -> {}, new CancellationToken());
        assertEquals(RunStatus.FAILED, teamResult.status());
        assertEquals("MODEL_UNSUPPORTED_TOOLS", teamResult.reason());
    }
}
