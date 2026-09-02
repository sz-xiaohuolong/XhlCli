package com.xhlcli.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.context.ContextAssembler;
import com.xhlcli.context.TokenBudget;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.llm.LlmClient;
import com.xhlcli.llm.LlmException;
import com.xhlcli.llm.StreamListener;
import com.xhlcli.memory.ConversationHistoryCompactor;
import com.xhlcli.memory.MemoryManager;
import com.xhlcli.model.ChatMessage;
import com.xhlcli.model.ChatResponse;
import com.xhlcli.model.TokenUsage;
import com.xhlcli.model.ToolDefinition;
import com.xhlcli.policy.PathGuard;
import com.xhlcli.tool.DefaultToolExecutor;
import com.xhlcli.tool.ToolRegistry;
import com.xhlcli.tool.ToolResultBudget;
import com.xhlcli.tool.ToolSchemaValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentMemoryIntegrationTest {

    @Test
    void testDynamicMemoryInjectionIntoPrompt(@TempDir Path globalDir, @TempDir Path projectDir) {
        MemoryManager memoryManager = new MemoryManager(globalDir, projectDir);
        TokenBudget budget = new TokenBudget(10000);
        ContextAssembler contextAssembler = new ContextAssembler(budget);

        AtomicReference<List<ChatMessage>> lastMessagesReceived = new AtomicReference<>();
        LlmClient capturingClient = new LlmClient() {
            @Override
            public ChatResponse stream(List<ChatMessage> messages, List<ToolDefinition> tools, StreamListener listener, CancellationToken cancellationToken) throws LlmException {
                lastMessagesReceived.set(new ArrayList<>(messages));
                return new ChatResponse("我知道了", new TokenUsage(10, 10, false));
            }
        };

        ObjectMapper mapper = new ObjectMapper();
        ToolRegistry registry = new ToolRegistry(List.of());
        DefaultToolExecutor executor = new DefaultToolExecutor(
                registry, new ToolSchemaValidator(mapper), new ToolResultBudget(1000, mapper),
                mapper, System::nanoTime, new PathGuard(projectDir), null, null);

        ReactAgent agent = new ReactAgent(
                ChatMessage.system("Base prompt"),
                capturingClient,
                executor,
                List.of(),
                new RunLimits(10, Duration.ofSeconds(10)),
                new ScheduledTimeoutScheduler(),
                mapper,
                Clock.systemUTC(),
                () -> UUID.randomUUID().toString(),
                s -> s,
                () -> new com.xhlcli.config.StreamingSecretRedactor("test-key")
        );

        agent.setContextAssembler(contextAssembler);
        agent.setCompactor(new ConversationHistoryCompactor(capturingClient));
        agent.setMemorySupplier(memoryManager::loadAll);

        // 1. 保存第一条记忆
        memoryManager.saveProject("我喜欢喝咖啡", "user");

        // 2. 执行第一轮对话
        agent.run("你好", event -> {}, new CancellationToken());

        List<ChatMessage> sentMessages = lastMessagesReceived.get();
        assertTrue(sentMessages != null && !sentMessages.isEmpty());
        String systemContent = sentMessages.get(0).content();
        assertTrue(systemContent.contains("我喜欢喝咖啡"), "第一次请求的 Prompt 中必须包含第一条记忆");

        // 3. 在同一会话中动态保存第二条全局记忆
        memoryManager.saveGlobal("我不吃辣", "user");

        // 4. 执行第二轮对话
        agent.run("你记住了什么？", event -> {}, new CancellationToken());

        sentMessages = lastMessagesReceived.get();
        systemContent = sentMessages.get(0).content();
        assertTrue(systemContent.contains("我喜欢喝咖啡"), "第二次请求的 Prompt 中仍然包含第一条记忆");
        assertTrue(systemContent.contains("我不吃辣"), "第二次请求的 Prompt 中动态生效了第二条记忆");
    }
}
