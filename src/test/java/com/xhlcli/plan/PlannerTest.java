package com.xhlcli.plan;

import com.xhlcli.llm.CancellationToken;
import com.xhlcli.llm.LlmClient;
import com.xhlcli.llm.LlmException;
import com.xhlcli.llm.StreamListener;
import com.xhlcli.model.ChatMessage;
import com.xhlcli.model.ChatResponse;
import com.xhlcli.model.TokenUsage;
import com.xhlcli.model.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerTest {

    @Test
    void createsMinimalPlanForSimpleGoalWithoutCallingLlm() throws Exception {
        Planner planner = new Planner(new FailingLlmClient());

        ExecutionPlan plan = planner.createPlan("列出当前目录的文件");

        assertEquals("直接执行简单任务：列出当前目录的文件", plan.getSummary());
        assertEquals(List.of("task_1"), plan.getExecutionOrder());
        Task task = plan.getTask("task_1");
        assertEquals(Task.TaskType.COMMAND, task.getType());
        assertEquals("列出当前目录的文件", task.getDescription());
    }

    @Test
    void delegatesComplexGoalToLlmPlannerPath() throws Exception {
        StubLlmClient client = new StubLlmClient("""
                {
                  "summary": "复杂任务",
                  "tasks": [
                    {
                      "id": "task_a",
                      "description": "先读取 pom.xml",
                      "type": "FILE_READ",
                      "dependencies": []
                    },
                    {
                      "id": "task_b",
                      "description": "再验证项目结构",
                      "type": "VERIFICATION",
                      "dependencies": ["task_a"]
                    }
                  ]
                }
                """);
        Planner planner = new Planner(client);
        planner.setProjectMemorySupplier(() -> "## 项目记忆\n- 计划前必须读取项目规则");

        ExecutionPlan plan = planner.createPlan("先读取 pom.xml 然后验证项目结构");

        assertEquals("复杂任务", plan.getSummary());
        assertEquals(2, plan.getAllTasks().size());
        assertTrue(plan.getTask("task_2").getDependencies().contains("task_1"));
        assertTrue(client.lastSystemPrompt.contains("计划前必须读取项目规则"));
    }

    @Test
    void rejectsCyclicPlanFromLlm() {
        StubLlmClient client = new StubLlmClient("""
                {
                  "summary": "循环计划",
                  "tasks": [
                    {
                      "id": "t1",
                      "description": "步骤1",
                      "type": "COMMAND",
                      "dependencies": ["t2"]
                    },
                    {
                      "id": "t2",
                      "description": "步骤2",
                      "type": "COMMAND",
                      "dependencies": ["t1"]
                    }
                  ]
                }
                """);
        Planner planner = new Planner(client);

        assertThrows(IOException.class, () -> planner.createPlan("先做步骤1然后做步骤2"));
    }

    @Test
    void replanConstructsContextWithCompletedTasks() throws Exception {
        StubLlmClient client = new StubLlmClient("""
                {
                  "summary": "重规划方案",
                  "tasks": [
                    {
                      "id": "t1",
                      "description": "替代步骤",
                      "type": "COMMAND",
                      "dependencies": []
                    }
                  ]
                }
                """);
        Planner planner = new Planner(client);

        ExecutionPlan failedPlan = new ExecutionPlan("failed-p", "先读取文件然后解析");
        Task t1 = new Task("t1", "读取文件", Task.TaskType.FILE_READ);
        t1.markCompleted("文件内容如下");
        Task t2 = new Task("t2", "解析文件", Task.TaskType.ANALYSIS, List.of("t1"));
        t2.markFailed("语法解析错误");
        failedPlan.addTask(t1);
        failedPlan.addTask(t2);

        ExecutionPlan replanned = planner.replan(failedPlan, "语法解析错误");

        assertEquals("重规划方案", replanned.getSummary());
        assertTrue(client.lastUserPrompt.contains("原任务: 先读取文件然后解析"));
        assertTrue(client.lastUserPrompt.contains("失败原因: 语法解析错误"));
        assertTrue(client.lastUserPrompt.contains("- t1: 读取文件"));
    }

    private static final class FailingLlmClient implements LlmClient {
        @Override
        public ChatResponse stream(List<ChatMessage> messages, List<ToolDefinition> tools,
                                   StreamListener listener, CancellationToken cancellationToken) throws LlmException {
            throw new LlmException(com.xhlcli.llm.LlmErrorType.INVALID_RESPONSE, "simple goal should not call llm", false, false);
        }
    }

    private static final class StubLlmClient implements LlmClient {
        private final String content;
        private String lastSystemPrompt = "";
        private String lastUserPrompt = "";

        private StubLlmClient(String content) {
            this.content = content;
        }

        @Override
        public ChatResponse stream(List<ChatMessage> messages, List<ToolDefinition> tools,
                                   StreamListener listener, CancellationToken cancellationToken) {
            for (ChatMessage msg : messages) {
                if (msg.role() == ChatMessage.Role.SYSTEM) {
                    this.lastSystemPrompt = msg.content();
                } else if (msg.role() == ChatMessage.Role.USER) {
                    this.lastUserPrompt = msg.content();
                }
            }
            if (listener != null) {
                listener.onTextDelta(content);
            }
            return new ChatResponse(content, List.of(), new TokenUsage(100, 20, true));
        }
    }
}
