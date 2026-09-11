package com.xhlcli.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.llm.LlmClient;
import com.xhlcli.llm.LlmException;
import com.xhlcli.llm.StreamListener;
import com.xhlcli.model.ChatMessage;
import com.xhlcli.model.ChatResponse;

import java.io.IOException;
import java.io.PrintStream;
import java.util.*;
import java.util.function.Supplier;

/**
 * 规划器 - 使用 LLM 将复杂任务分解为执行计划
 */
public class Planner {
    private final LlmClient llmClient;
    private final PrintStream out;
    private final ObjectMapper mapper = new ObjectMapper();
    private Supplier<String> projectMemorySupplier = () -> "";

    private static final String PLANNER_SYSTEM_PROMPT = """
            ## Mode: Plan Builder

            你是一个任务规划专家。请将用户的复杂任务分解为一系列可执行的子任务。

            可用任务类型：

            - `FILE_READ`: 读取文件内容
            - `FILE_WRITE`: 写入文件内容
            - `COMMAND`: 执行 Shell 命令
            - `ANALYSIS`: 分析结果并做出决策
            - `VERIFICATION`: 验证结果是否正确

            请按以下 JSON 格式输出执行计划：

            ```json
            {
              "summary": "任务摘要",
              "tasks": [
                {
                  "id": "task_1",
                  "description": "任务描述",
                  "type": "FILE_READ",
                  "dependencies": []
                }
              ]
            }
            ```

            规则：

            1. 每个任务必须有唯一 id，如 `task_1`、`task_2`。
            2. `dependencies` 列出依赖的任务 id。
            3. 任务应该按执行顺序排列。
            4. 任务描述要具体明确。
            5. 简单任务允许只生成 1-3 个任务，不要为了凑步数引入无关步骤。
            6. 复杂任务拆分为 5-10 个子任务。
            7. 不要为了“保存中间结果”额外创建 `FILE_WRITE` / `FILE_READ`，除非用户明确要求落盘。
            8. 如果一个任务一步就能完成，就保持最短计划。

            只输出 JSON，不要有其他内容。
            """;

    public Planner(LlmClient llmClient) {
        this(llmClient, System.out);
    }

    public Planner(LlmClient llmClient, PrintStream out) {
        this.llmClient = llmClient;
        this.out = out == null ? System.out : out;
    }

    public void setProjectMemorySupplier(Supplier<String> projectMemorySupplier) {
        this.projectMemorySupplier = projectMemorySupplier == null ? () -> "" : projectMemorySupplier;
    }

    /**
     * 为任务创建执行计划
     */
    public ExecutionPlan createPlan(String goal) throws IOException {
        return createPlan(goal, new CancellationToken());
    }

    public ExecutionPlan createPlan(String goal, CancellationToken cancellationToken) throws IOException {
        out.println("📋 正在规划任务: " + goal + "\n");

        if (isSimpleGoal(goal)) {
            return createMinimalPlan(goal);
        }

        String systemPrompt = PLANNER_SYSTEM_PROMPT;
        String memoryContext = buildProjectMemoryContext();
        if (!memoryContext.isEmpty()) {
            systemPrompt = systemPrompt + "\n\n## 项目记忆\n" + memoryContext;
        }

        List<ChatMessage> messages = List.of(
                ChatMessage.system(systemPrompt),
                ChatMessage.user("请为以下任务制定执行计划：\n" + goal)
        );

        StringBuilder streamedContent = new StringBuilder();
        StreamListener listener = delta -> {
            if (delta != null) {
                streamedContent.append(delta);
            }
        };

        try {
            ChatResponse response = llmClient.stream(messages, listener, cancellationToken);
            String planJson = response.content();
            if (planJson == null || planJson.isBlank()) {
                planJson = streamedContent.toString();
            }
            return parsePlan(goal, planJson);
        } catch (LlmException e) {
            throw new IOException("调用 LLM 规划任务失败: " + e.getMessage(), e);
        }
    }

    private String buildProjectMemoryContext() {
        try {
            String context = projectMemorySupplier.get();
            return context == null ? "" : context.trim();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 解析 LLM 生成的计划 JSON
     */
    private ExecutionPlan parsePlan(String goal, String planJson) throws IOException {
        String cleaned = planJson.replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .trim();

        JsonNode root = mapper.readTree(cleaned);
        String summary = root.path("summary").asText();
        JsonNode tasksNode = root.path("tasks");

        ExecutionPlan plan = new ExecutionPlan(generatePlanId(), goal);
        plan.setSummary(summary);

        Map<String, String> idMapping = new HashMap<>();
        int taskIndex = 1;

        for (JsonNode taskNode : tasksNode) {
            String originalId = taskNode.path("id").asText();
            String newId = "task_" + taskIndex++;
            idMapping.put(originalId, newId);

            String description = taskNode.path("description").asText();
            String typeStr = taskNode.path("type").asText();
            Task.TaskType type = parseTaskType(typeStr);

            plan.addTask(new Task(newId, description, type));
        }

        taskIndex = 1;
        for (JsonNode taskNode : tasksNode) {
            String newId = "task_" + taskIndex++;
            Task task = plan.getTask(newId);

            JsonNode depsNode = taskNode.path("dependencies");
            if (depsNode.isArray()) {
                for (JsonNode depNode : depsNode) {
                    String originalDepId = depNode.asText();
                    String newDepId = idMapping.getOrDefault(originalDepId, originalDepId);
                    Task dep = plan.getTask(newDepId);
                    if (dep != null) {
                        task.addDependency(newDepId);
                        dep.addDependent(task.getId());
                    }
                }
            }
        }

        if (!plan.computeExecutionOrder()) {
            throw new IOException("计划中存在循环依赖");
        }

        return plan;
    }

    private Task.TaskType parseTaskType(String typeStr) {
        if (typeStr == null) {
            return Task.TaskType.ANALYSIS;
        }
        return switch (typeStr.toUpperCase(Locale.ROOT)) {
            case "FILE_READ" -> Task.TaskType.FILE_READ;
            case "FILE_WRITE" -> Task.TaskType.FILE_WRITE;
            case "COMMAND" -> Task.TaskType.COMMAND;
            case "ANALYSIS" -> Task.TaskType.ANALYSIS;
            case "VERIFICATION" -> Task.TaskType.VERIFICATION;
            default -> Task.TaskType.ANALYSIS;
        };
    }

    private String generatePlanId() {
        return "plan_" + System.currentTimeMillis();
    }

    /**
     * 根据执行结果重新规划
     */
    public ExecutionPlan replan(ExecutionPlan failedPlan, String failureReason) throws IOException {
        return replan(failedPlan, failureReason, new CancellationToken());
    }

    public ExecutionPlan replan(ExecutionPlan failedPlan, String failureReason, CancellationToken cancellationToken) throws IOException {
        out.println("🔄 重新规划，原因: " + failureReason + "\n");

        StringBuilder context = new StringBuilder();
        context.append("原任务: ").append(failedPlan.getGoal()).append("\n");
        context.append("失败原因: ").append(failureReason).append("\n");
        context.append("已完成的任务:\n");

        for (Task task : failedPlan.getAllTasks()) {
            if (task.getStatus() == Task.TaskStatus.COMPLETED) {
                context.append("- ").append(task.getId())
                        .append(": ").append(task.getDescription())
                        .append("\n");
            }
        }

        context.append("\n请制定新的执行计划，避开之前的问题。");

        return createPlan(context.toString(), cancellationToken);
    }

    private boolean isSimpleGoal(String goal) {
        if (goal == null) {
            return false;
        }

        String normalized = goal.trim();
        if (normalized.isEmpty()) {
            return false;
        }

        boolean hasMultiStepCue = normalized.contains("然后")
                || normalized.contains("并且")
                || normalized.contains("并")
                || normalized.contains("再")
                || normalized.contains("最后")
                || normalized.contains("同时")
                || normalized.contains("先")
                || normalized.contains("之后")
                || normalized.contains("接着")
                || normalized.contains("以及");
        if (hasMultiStepCue) {
            return false;
        }

        if (normalized.length() > 30) {
            return false;
        }

        return normalized.contains("列出")
                || normalized.contains("查看")
                || normalized.contains("读取")
                || normalized.contains("显示")
                || normalized.contains("执行")
                || normalized.contains("运行")
                || normalized.contains("搜索")
                || normalized.contains("当前目录")
                || normalized.contains("文件");
    }

    private ExecutionPlan createMinimalPlan(String goal) {
        ExecutionPlan plan = new ExecutionPlan(generatePlanId(), goal);
        plan.setSummary(buildMinimalSummary(goal));
        plan.addTask(new Task("task_1", goal.trim(), inferSimpleTaskType(goal)));
        if (!plan.computeExecutionOrder()) {
            throw new IllegalStateException("简单计划不应出现循环依赖");
        }
        return plan;
    }

    private String buildMinimalSummary(String goal) {
        String normalized = goal == null ? "" : goal.trim();
        if (normalized.isEmpty()) {
            return "执行简单任务";
        }
        return "直接执行简单任务：" + normalized;
    }

    private Task.TaskType inferSimpleTaskType(String goal) {
        String normalized = goal == null ? "" : goal.trim();
        if (normalized.contains("读取") || normalized.contains("打开") || (normalized.contains("查看")
                && normalized.contains("文件"))) {
            return Task.TaskType.FILE_READ;
        }
        if (normalized.contains("写入") || normalized.contains("修改") || normalized.contains("创建文件")) {
            return Task.TaskType.FILE_WRITE;
        }
        if (normalized.contains("分析") || normalized.contains("总结") || normalized.contains("解释")) {
            return Task.TaskType.ANALYSIS;
        }
        if (normalized.contains("验证") || normalized.contains("检查")) {
            return Task.TaskType.VERIFICATION;
        }
        return Task.TaskType.COMMAND;
    }
}
