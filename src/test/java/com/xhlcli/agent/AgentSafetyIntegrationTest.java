package com.xhlcli.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.config.StreamingSecretRedactor;
import com.xhlcli.hitl.ApprovalRequest;
import com.xhlcli.hitl.ApprovalResult;
import com.xhlcli.hitl.HitlHandler;
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
import com.xhlcli.model.ToolDefinition;
import com.xhlcli.policy.AuditLog;
import com.xhlcli.policy.PathGuard;
import com.xhlcli.tool.DefaultToolExecutor;
import com.xhlcli.tool.ToolRegistry;
import com.xhlcli.tool.ToolResultBudget;
import com.xhlcli.tool.ToolSchemaValidator;
import com.xhlcli.tool.local.ExecuteCommandTool;
import com.xhlcli.tool.local.WorkspacePathResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSafetyIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void agentRecoversFromPolicyViolationAndSucceedsWithHitlApproval(@TempDir Path tempDir) {
        PathGuard pathGuard = new PathGuard(tempDir);
        WorkspacePathResolver resolver = new WorkspacePathResolver(tempDir);
        AuditLog auditLog = new AuditLog(tempDir.resolve("audit"));
        AtomicInteger hitlCount = new AtomicInteger(0);

        HitlHandler hitlHandler = new HitlHandler() {
            @Override
            public ApprovalResult requestApproval(ApprovalRequest request) {
                hitlCount.incrementAndGet();
                return ApprovalResult.approve();
            }

            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public void setEnabled(boolean enabled) {}
        };

        ToolRegistry registry = new ToolRegistry(List.of(new ExecuteCommandTool(resolver)));

        DefaultToolExecutor executor = new DefaultToolExecutor(
                registry,
                new ToolSchemaValidator(mapper),
                new ToolResultBudget(1000, mapper),
                mapper,
                System::nanoTime,
                pathGuard,
                hitlHandler,
                auditLog);

        // 模拟 LLM：
        // Turn 1: 尝试执行 sudo 命令（会被硬策略拦截）
        // Turn 2: 收到策略拒绝后，改为执行 safe 命令 git status（HITL 审批放行）
        // Turn 3: 给出最终回复
        ScriptedClient client = new ScriptedClient(List.of(
                new ChatResponse(
                        "",
                        List.of(new ToolCall("call_1", "execute_command", "{\"command\":\"sudo rm -rf /tmp/test\"}")),
                        TokenUsage.unknown()),
                new ChatResponse(
                        "",
                        List.of(new ToolCall("call_2", "execute_command", "{\"command\":\"git status\"}")),
                        TokenUsage.unknown()),
                new ChatResponse(
                        "已安全完成检查。",
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
        RunResult result = agent.run("Clean up temp files", events::add, new CancellationToken());

        assertEquals(RunStatus.COMPLETED, result.status());
        assertEquals("已安全完成检查。", result.finalAnswer());
        assertEquals(2, result.iterations());

        // 第一次硬策略拒绝未调用 HITL，第二次安全命令调用了 1 次 HITL 审批
        assertEquals(1, hitlCount.get());

        // 验证审计日志
        List<AuditLog.AuditEntry> audits = auditLog.readRecent(10);
        assertEquals(2, audits.size());
        assertEquals("deny", audits.get(0).outcome());
        assertEquals("policy", audits.get(0).approver());
        assertEquals("allow", audits.get(1).outcome());
    }

    private static final class NoopTimeoutScheduler implements TimeoutScheduler {
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
                List<ToolDefinition> tools,
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
