package com.xhlcli.team;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.llm.LlmClient;
import com.xhlcli.llm.LlmErrorType;
import com.xhlcli.llm.LlmException;
import com.xhlcli.llm.StreamListener;
import com.xhlcli.model.*;
import com.xhlcli.tool.ToolExecutor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SubAgentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ToolDefinition DUMMY_TOOL = new ToolDefinition(
            "dummy_tool",
            "A test dummy tool",
            MAPPER.createObjectNode(),
            ToolMetadata.conservative()
    );

    @Test
    void testToolIsolationByRole() {
        StubLlmClient llm = new StubLlmClient(List.of("规划完成"));

        // Planner: 严禁工具
        SubAgent planner = new SubAgent("planner", TeamRole.PLANNER, llm, null, List.of(DUMMY_TOOL));
        assertTrue(planner.getToolDefinitions().isEmpty(), "Planner 角色必须强制屏蔽所有工具");

        // Reviewer: 严禁工具
        SubAgent reviewer = new SubAgent("reviewer", TeamRole.REVIEWER, llm, null, List.of(DUMMY_TOOL));
        assertTrue(reviewer.getToolDefinitions().isEmpty(), "Reviewer 角色必须强制屏蔽所有工具");

        // Worker: 允许工具
        SubAgent worker = new SubAgent("worker", TeamRole.WORKER, llm, null, List.of(DUMMY_TOOL));
        assertEquals(1, worker.getToolDefinitions().size(), "Worker 角色应保留工具定义");
        assertEquals("dummy_tool", worker.getToolDefinitions().get(0).name());
    }

    @Test
    void testSystemPromptInitializationAndClearHistory() {
        StubLlmClient llm = new StubLlmClient(List.of("执行第一步成功"));
        SubAgent worker = new SubAgent("worker-1", TeamRole.WORKER, llm);

        // 验证初始系统提示词
        assertEquals(1, worker.getConversationHistory().size());
        assertEquals(ChatMessage.Role.SYSTEM, worker.getConversationHistory().get(0).role());
        assertTrue(worker.getConversationHistory().get(0).content().contains("Worker"));

        // 执行任务后 history 增加
        worker.execute(TeamMessage.task("orchestrator", "执行步骤1"));
        assertTrue(worker.getConversationHistory().size() > 1);

        // clearHistory 之后恢复为只有 1 条系统消息
        worker.clearHistory();
        assertEquals(1, worker.getConversationHistory().size());
        assertEquals(ChatMessage.Role.SYSTEM, worker.getConversationHistory().get(0).role());
    }

    @Test
    void testWorkerToolExecutionLoop() {
        ToolCall call = new ToolCall("call-1", "dummy_tool", "{\"foo\":\"bar\"}");
        ChatResponse step1WithTool = new ChatResponse(
                "正在调用工具获取数据",
                List.of(call),
                new TokenUsage(10, 10, true)
        );
        ChatResponse step2Finish = new ChatResponse(
                "已根据工具结果完成操作",
                List.of(),
                new TokenUsage(15, 10, true)
        );

        StubLlmClient llm = new StubLlmClient(step1WithTool, step2Finish);

        List<String> executedTools = new ArrayList<>();
        ToolExecutor executor = (toolCall, token) -> {
            executedTools.add(toolCall.name());
            return new ToolResult(
                    toolCall.id(),
                    toolCall.name(),
                    ToolResultStatus.SUCCESS,
                    "执行成功",
                    MAPPER.createObjectNode().put("result", "ok"),
                    10,
                    false,
                    20,
                    ""
            );
        };

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(baos, true, StandardCharsets.UTF_8);

        SubAgent worker = new SubAgent("worker-1", TeamRole.WORKER, llm, executor, List.of(DUMMY_TOOL));
        TeamMessage result = worker.execute(TeamMessage.task("orchestrator", "请调用工具"), out, new CancellationToken());

        assertEquals(TeamMessage.Type.RESULT, result.type());
        assertEquals("已根据工具结果完成操作", result.content());
        assertEquals(1, executedTools.size());
        assertEquals("dummy_tool", executedTools.get(0));

        String printed = baos.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("调用工具: dummy_tool"));
    }

    @Test
    void testExecuteWithCancellation() {
        StubLlmClient llm = new StubLlmClient(List.of("响应"));
        SubAgent worker = new SubAgent("worker-1", TeamRole.WORKER, llm);

        CancellationToken token = new CancellationToken();
        token.cancel();

        TeamMessage message = worker.execute(TeamMessage.task("orchestrator", "执行"), null, token);
        assertEquals(TeamMessage.Type.ERROR, message.type());
        assertTrue(message.content().contains("取消"));
    }

    @Test
    void testReviewerReview() {
        String reviewJson = """
                {
                  "approved": true,
                  "summary": "审查合格",
                  "issues": [],
                  "suggestions": []
                }
                """;
        StubLlmClient llm = new StubLlmClient(List.of(reviewJson));
        SubAgent reviewer = new SubAgent("reviewer", TeamRole.REVIEWER, llm);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(baos, true, StandardCharsets.UTF_8);

        TeamMessage message = reviewer.review("任务目标", "代码已写好", out, new CancellationToken());
        assertEquals(TeamMessage.Type.RESULT, message.type());
        assertTrue(message.content().contains("\"approved\": true"));

        ReviewResult parsed = ReviewResult.parse(message.content());
        assertTrue(parsed.isApproved());
    }

    private static final class StubLlmClient implements LlmClient {
        private final Queue<ChatResponse> responses = new ArrayDeque<>();

        private StubLlmClient(List<String> textResponses) {
            for (String text : textResponses) {
                responses.add(new ChatResponse(text, List.of(), new TokenUsage(10, 5, true)));
            }
        }

        private StubLlmClient(ChatResponse... customResponses) {
            responses.addAll(Arrays.asList(customResponses));
        }

        @Override
        public ChatResponse stream(
                List<ChatMessage> messages,
                List<ToolDefinition> tools,
                StreamListener listener,
                CancellationToken cancellationToken) throws LlmException {
            if (cancellationToken != null && cancellationToken.isCancelled()) {
                throw new LlmException(LlmErrorType.CANCELLED, "Cancelled", false, false);
            }
            ChatResponse next = responses.poll();
            if (next == null) {
                return new ChatResponse("默认完成回答", List.of(), new TokenUsage(5, 5, true));
            }
            if (listener != null && next.content() != null && !next.content().isEmpty()) {
                listener.onTextDelta(next.content());
            }
            return next;
        }
    }
}
