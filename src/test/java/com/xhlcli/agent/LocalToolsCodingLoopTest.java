package com.xhlcli.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.config.StreamingSecretRedactor;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.llm.LlmClient;
import com.xhlcli.llm.StreamListener;
import com.xhlcli.model.ChatMessage;
import com.xhlcli.model.ChatResponse;
import com.xhlcli.model.RunEvent;
import com.xhlcli.model.RunResult;
import com.xhlcli.model.RunStatus;
import com.xhlcli.model.TokenUsage;
import com.xhlcli.model.ToolCall;
import com.xhlcli.tool.DefaultToolExecutor;
import com.xhlcli.tool.ToolRegistry;
import com.xhlcli.tool.ToolResultBudget;
import com.xhlcli.tool.ToolSchemaValidator;
import com.xhlcli.tool.local.ApplyPatchTool;
import com.xhlcli.tool.local.ExecuteCommandTool;
import com.xhlcli.tool.local.GitDiffTool;
import com.xhlcli.tool.local.GlobFilesTool;
import com.xhlcli.tool.local.ListDirTool;
import com.xhlcli.tool.local.ReadFileTool;
import com.xhlcli.tool.local.WorkspacePathResolver;
import com.xhlcli.tool.local.WriteFileTool;
import com.xhlcli.tool.local.search.GrepCodeTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalToolsCodingLoopTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void multiStepCodingWorkflowExecutesActualLocalTools(@TempDir Path tempDir) throws Exception {
        WorkspacePathResolver resolver = new WorkspacePathResolver(tempDir);
        ToolRegistry registry = new ToolRegistry(List.of(
                new ListDirTool(resolver),
                new ReadFileTool(resolver),
                new WriteFileTool(resolver),
                new ApplyPatchTool(resolver),
                new GitDiffTool(resolver),
                new ExecuteCommandTool(resolver),
                new GlobFilesTool(resolver),
                new GrepCodeTool(resolver)));

        DefaultToolExecutor executor = new DefaultToolExecutor(
                registry,
                new ToolSchemaValidator(mapper),
                new ToolResultBudget(ToolResultBudget.DEFAULT_MAX_CHARS, mapper),
                mapper,
                System::nanoTime);

        // Simulated LLM turn sequence:
        // 1. write_file
        // 2. read_file
        // 3. apply_patch
        // 4. execute_command
        // 5. final answer
        ScriptedClient client = new ScriptedClient(List.of(
                new ChatResponse(
                        "",
                        List.of(new ToolCall(
                                "call_1",
                                "write_file",
                                "{\"path\":\"Calculator.java\",\"content\":\"public class Calculator { public int add(int a, int b) { return a - b; } }\"}")),
                        TokenUsage.unknown()),
                new ChatResponse(
                        "",
                        List.of(new ToolCall(
                                "call_2",
                                "read_file",
                                "{\"path\":\"Calculator.java\"}")),
                        TokenUsage.unknown()),
                new ChatResponse(
                        "",
                        List.of(new ToolCall(
                                "call_3",
                                "apply_patch",
                                "{\"path\":\"Calculator.java\",\"old_text\":\"return a - b;\",\"new_text\":\"return a + b;\"}")),
                        TokenUsage.unknown()),
                new ChatResponse(
                        "",
                        List.of(new ToolCall(
                                "call_4",
                                "execute_command",
                                "{\"command\":\"cat Calculator.java\"}")),
                        TokenUsage.unknown()),
                new ChatResponse(
                        "Fixed the bug in Calculator.java.",
                        List.of(),
                        TokenUsage.unknown())));

        ReactAgent agent = new ReactAgent(
                ChatMessage.system("You are XhlCLI."),
                client,
                executor,
                registry.definitions(),
                new RunLimits(10, Duration.ofMinutes(1)),
                new NoopTimeoutScheduler(),
                mapper,
                Clock.systemUTC(),
                () -> UUID.randomUUID().toString(),
                val -> val,
                () -> new StreamingSecretRedactor(""));

        List<RunEvent> events = new ArrayList<>();
        RunResult result = agent.run("Fix the addition method in Calculator.java", events::add, new CancellationToken());

        assertEquals(RunStatus.COMPLETED, result.status());
        assertEquals("Fixed the bug in Calculator.java.", result.finalAnswer());
        assertEquals(4, result.iterations());

        Path calculatorFile = tempDir.resolve("Calculator.java");
        assertTrue(Files.exists(calculatorFile));
        assertEquals("public class Calculator { public int add(int a, int b) { return a + b; } }",
                Files.readString(calculatorFile));
    }

    private static final class NoopTimeoutScheduler implements com.xhlcli.agent.TimeoutScheduler {
        @Override
        public Registration schedule(Duration delay, Runnable action) {
            return () -> {};
        }

        @Override
        public void close() {}
    }

    private static final class ScriptedClient implements LlmClient {
        private final Deque<ChatResponse> responses;

        private ScriptedClient(List<ChatResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse stream(
                List<ChatMessage> messages,
                List<com.xhlcli.model.ToolDefinition> tools,
                StreamListener listener,
                CancellationToken token) {
            if (responses.isEmpty()) {
                throw new IllegalStateException("No more scripted responses");
            }
            ChatResponse response = responses.removeFirst();
            if (!response.content().isEmpty()) {
                listener.onTextDelta(response.content());
            }
            return response;
        }
    }
}
