package com.xhlcli.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.cli.PlanReviewInputParser;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.llm.LlmClient;
import com.xhlcli.memory.MemoryManager;
import com.xhlcli.model.*;
import com.xhlcli.plan.ExecutionPlan;
import com.xhlcli.plan.Planner;
import com.xhlcli.plan.Task;
import com.xhlcli.tool.ToolExecutor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Plan-and-Execute Agent - 先规划后执行，支持 DAG 拓扑分层调度与人机审阅交互
 */
public class PlanExecuteAgent implements AgentRunner {
    private static final int MAX_TASK_ITERATIONS = 5;

    public interface PlanReviewHandler {
        PlanReviewDecision review(String goal, ExecutionPlan plan);
    }

    public enum PlanReviewAction {
        EXECUTE,
        SUPPLEMENT,
        CANCEL
    }

    public record PlanReviewDecision(PlanReviewAction action, String feedback) {
        public static PlanReviewDecision execute() {
            return new PlanReviewDecision(PlanReviewAction.EXECUTE, null);
        }

        public static PlanReviewDecision supplement(String feedback) {
            return new PlanReviewDecision(PlanReviewAction.SUPPLEMENT, feedback);
        }

        public static PlanReviewDecision cancel() {
            return new PlanReviewDecision(PlanReviewAction.CANCEL, null);
        }
    }

    private record PlanRunOutcome(String result, boolean persistAssistantMessage, RunStatus status) {
        static PlanRunOutcome executed(String result) {
            return new PlanRunOutcome(result, true, RunStatus.COMPLETED);
        }

        static PlanRunOutcome canceled(String result) {
            return new PlanRunOutcome(result, false, RunStatus.CANCELED);
        }

        static PlanRunOutcome failed(String result) {
            return new PlanRunOutcome(result, true, RunStatus.FAILED);
        }
    }

    private record TaskExecutionResult(Task task, String result, Exception error) {
        static TaskExecutionResult success(Task task, String result) {
            return new TaskExecutionResult(task, result, null);
        }

        static TaskExecutionResult failure(Task task, Exception error) {
            return new TaskExecutionResult(task, null, error);
        }

        boolean failed() {
            return error != null;
        }
    }

    private volatile LlmClient llmClient;
    private final ToolExecutor toolExecutor;
    private volatile List<ToolDefinition> toolDefinitions;
    private final Planner planner;

    public void setClient(LlmClient client) {
        this.llmClient = Objects.requireNonNull(client, "client");
        if (this.planner != null) {
            this.planner.setClient(client);
        }
    }

    public void setToolDefinitions(List<ToolDefinition> toolDefinitions) {
        this.toolDefinitions = toolDefinitions != null ? List.copyOf(toolDefinitions) : List.of();
    }

    public LlmClient getClient() {
        return llmClient;
    }
    private final PlanReviewHandler reviewHandler;
    private final MemoryManager memoryManager;
    private final PrintStream out;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<ChatMessage> committedHistory = new ArrayList<>();
    private int maxConcurrency = 4;

    public void setMaxConcurrency(int maxConcurrency) {
        this.maxConcurrency = Math.max(1, Math.min(16, maxConcurrency));
    }

    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    public PlanExecuteAgent(LlmClient llmClient) {
        this(llmClient, (goal, plan) -> PlanReviewDecision.execute());
    }

    public PlanExecuteAgent(LlmClient llmClient, PlanReviewHandler reviewHandler) {
        this(llmClient, null, List.of(), null, null, reviewHandler, System.out);
    }

    public PlanExecuteAgent(LlmClient llmClient, ToolExecutor toolExecutor,
                            List<ToolDefinition> toolDefinitions, PlanReviewHandler reviewHandler) {
        this(llmClient, toolExecutor, toolDefinitions, null, null, reviewHandler, System.out);
    }

    public PlanExecuteAgent(LlmClient llmClient, ToolExecutor toolExecutor,
                            List<ToolDefinition> toolDefinitions, Planner planner,
                            MemoryManager memoryManager, PlanReviewHandler reviewHandler) {
        this(llmClient, toolExecutor, toolDefinitions, planner, memoryManager, reviewHandler, System.out);
    }

    public PlanExecuteAgent(LlmClient llmClient, ToolExecutor toolExecutor,
                            List<ToolDefinition> toolDefinitions, Planner planner,
                            MemoryManager memoryManager, PlanReviewHandler reviewHandler,
                            PrintStream out) {
        this.llmClient = llmClient;
        this.toolExecutor = toolExecutor;
        this.toolDefinitions = toolDefinitions != null ? List.copyOf(toolDefinitions) : List.of();
        this.out = out == null ? System.out : out;
        this.planner = planner != null ? planner : new Planner(llmClient, this.out);
        this.reviewHandler = reviewHandler != null ? reviewHandler : (goal, plan) -> PlanReviewDecision.execute();
        this.memoryManager = memoryManager;
    }

    public String run(String userInput) {
        return run(userInput, new CancellationToken());
    }

    public String run(String userInput, CancellationToken cancellationToken) {
        RunResult result = run(userInput, event -> {}, cancellationToken);
        return result.finalAnswer();
    }

    @Override
    public RunResult run(String input, RunEventSink events, CancellationToken cancellationToken) {
        String runId = "plan_run_" + UUID.randomUUID();
        if (input == null || input.isBlank()) {
            return new RunResult(runId, RunStatus.COMPLETED, "", "Empty input", 0, TokenUsage.unknown());
        }

        committedHistory.add(ChatMessage.user(input));
        if (cancellationToken != null && cancellationToken.isCancelled()) {
            return new RunResult(runId, RunStatus.CANCELED, "⏹️ 已取消当前计划执行。", "Cancelled", 0, TokenUsage.unknown());
        }
        if (!toolDefinitions.isEmpty() && !llmClient.capabilities().supportsTools()) {
            String msg = "当前模型 [" + llmClient.modelName() + "] 不支持工具调用，无法执行 Plan 任务。请使用 /model use 切换模型。";
            events.accept(new RunEvent.TextDelta(new RunEvent.Metadata(runId, 1, java.time.Instant.now(), 0), msg));
            return new RunResult(runId, RunStatus.FAILED, msg, "MODEL_UNSUPPORTED_TOOLS", 0, TokenUsage.unknown());
        }

        try {
            ExecutionPlan plan = planner.createPlan(input, cancellationToken);
            PlanRunOutcome outcome = reviewAndExecutePlan(plan, events, cancellationToken);

            if (outcome.persistAssistantMessage() && outcome.result() != null && !outcome.result().isBlank()) {
                committedHistory.add(ChatMessage.assistant("[计划结果] " + outcome.result()));
                if (memoryManager != null) {
                    memoryManager.saveProject("用户目标: " + input + "\n计划执行结果: " + outcome.result(), "plan_agent");
                }
            }

            return new RunResult(runId, outcome.status(), outcome.result(), "Plan finished", 1, TokenUsage.unknown());
        } catch (Exception e) {
            String errorMsg = "❌ 执行失败: " + e.getMessage();
            committedHistory.add(ChatMessage.assistant(errorMsg));
            return new RunResult(runId, RunStatus.FAILED, errorMsg, e.getMessage(), 0, TokenUsage.unknown());
        }
    }

    @Override
    public void clearHistory() {
        committedHistory.clear();
    }

    @Override
    public List<ChatMessage> history() {
        return List.copyOf(committedHistory);
    }

    private static final int MAX_REPLAN_ATTEMPTS = 2;

    private PlanRunOutcome reviewAndExecutePlan(ExecutionPlan plan, RunEventSink events,
                                                CancellationToken cancellationToken) throws IOException {
        return reviewAndExecutePlan(plan, events, cancellationToken, 0);
    }

    private PlanRunOutcome reviewAndExecutePlan(ExecutionPlan plan, RunEventSink events,
                                                CancellationToken cancellationToken, int replanCount) throws IOException {
        while (true) {
            if (cancellationToken != null && cancellationToken.isCancelled()) {
                return PlanRunOutcome.canceled("⏹️ 已取消本次计划执行。");
            }

            PlanReviewDecision decision = reviewHandler.review(plan.getGoal(), plan);
            if (decision == null || decision.action() == PlanReviewAction.EXECUTE) {
                return PlanRunOutcome.executed(executePlan(plan, events, cancellationToken, replanCount));
            }

            if (decision.action() == PlanReviewAction.CANCEL) {
                return PlanRunOutcome.canceled("⏹️ 已取消本次计划执行。");
            }

            String feedback = decision.feedback() == null ? "" : decision.feedback().trim();
            if (feedback.isEmpty()) {
                return PlanRunOutcome.executed(executePlan(plan, events, cancellationToken, replanCount));
            }

            out.println("📝 已收到补充要求，正在重新规划...\n");
            plan = planner.createPlan(plan.getGoal() + "\n补充要求：" + feedback, cancellationToken);
        }
    }

    private String executePlan(ExecutionPlan plan, RunEventSink events,
                               CancellationToken cancellationToken, int replanCount) throws IOException {
        out.println("🚀 开始执行计划...\n");
        plan.markStarted();
        StringBuilder finalResult = new StringBuilder();

        while (true) {
            if (cancellationToken != null && cancellationToken.isCancelled()) {
                return "⏹️ 已取消当前计划执行。";
            }

            List<Task> executableTasks = getExecutableTasksInOrder(plan);
            if (executableTasks.isEmpty()) {
                break;
            }

            List<TaskExecutionResult> batchResults = executeTaskBatch(plan, executableTasks, cancellationToken);
            for (TaskExecutionResult batchResult : batchResults) {
                Task task = batchResult.task();
                if (!batchResult.failed()) {
                    task.markCompleted(batchResult.result());
                    String displayResult = batchResult.result() != null && !batchResult.result().isBlank()
                            ? ": " + batchResult.result().substring(0, Math.min(100, batchResult.result().length()))
                            : "";
                    out.println("✅ 完成 [" + task.getId() + "]" + displayResult + "\n");
                    continue;
                }

                Exception error = batchResult.error();
                task.markFailed(error.getMessage());
                out.println("❌ 失败 [" + task.getId() + "]: " + error.getMessage() + "\n");

                if (plan.getProgress() < 0.5 && replanCount < MAX_REPLAN_ATTEMPTS) {
                    out.println("🔄 尝试重新规划...\n");
                    ExecutionPlan replanned = planner.replan(plan, error.getMessage(), cancellationToken);
                    return reviewAndExecutePlan(replanned, events, cancellationToken, replanCount + 1).result();
                }

                if (!finalResult.isEmpty()) {
                    finalResult.append("\n");
                }
                finalResult.append("任务 ").append(task.getId()).append(" 失败: ").append(error.getMessage());
            }
        }

        if (!plan.isAllCompleted() && !plan.hasFailed()) {
            plan.markFailed();
            return "⚠️ 计划未能继续推进，存在未满足依赖的任务。";
        }

        String planSummary = finalResult.isEmpty()
                ? buildFinalResult(plan)
                : finalResult.toString();

        if (plan.hasFailed()) {
            plan.markFailed();
            return planSummary.isBlank() ? "⚠️ 计划部分完成，有任务失败。" : "⚠️ 计划部分完成，有任务失败。\n" + planSummary;
        }

        plan.markCompleted();
        return planSummary.isBlank() ? "✅ 计划执行完成！" : "✅ 计划执行完成！\n" + planSummary;
    }

    private List<Task> getExecutableTasksInOrder(ExecutionPlan plan) {
        Set<String> executableIds = plan.getExecutableTasks().stream()
                .map(Task::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return plan.getExecutionOrder().stream()
                .filter(executableIds::contains)
                .map(plan::getTask)
                .toList();
    }

    private List<TaskExecutionResult> executeTaskBatch(ExecutionPlan plan, List<Task> executableTasks,
                                                       CancellationToken cancellationToken) {
        if (executableTasks.size() == 1) {
            Task task = executableTasks.get(0);
            out.println("▶️ 执行任务 [" + task.getId() + "]: " + task.getDescription());
            task.markStarted();
            try {
                return List.of(TaskExecutionResult.success(task, executeTask(plan.getGoal(), plan, task, out, cancellationToken)));
            } catch (Exception e) {
                return List.of(TaskExecutionResult.failure(task, e));
            }
        }

        // 若可执行任务数超出 maxConcurrency，分批次调度执行
        if (executableTasks.size() > maxConcurrency) {
            List<TaskExecutionResult> combinedResults = new ArrayList<>(executableTasks.size());
            for (int i = 0; i < executableTasks.size(); i += maxConcurrency) {
                int end = Math.min(i + maxConcurrency, executableTasks.size());
                List<Task> chunk = executableTasks.subList(i, end);
                combinedResults.addAll(executeTaskBatch(plan, chunk, cancellationToken));
            }
            return combinedResults;
        }

        String parallelTaskIds = executableTasks.stream()
                .map(Task::getId)
                .collect(Collectors.joining(", "));
        out.println("⚡ 本轮并行执行 " + executableTasks.size() + " 个任务: " + parallelTaskIds);

        int poolSize = Math.min(executableTasks.size(), maxConcurrency);
        ExecutorService executor = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "xhlcli-plan-worker");
            t.setDaemon(true);
            return t;
        });

        try {
            Map<String, ByteArrayOutputStream> buffers = new LinkedHashMap<>();
            List<Future<TaskExecutionResult>> futures = new ArrayList<>();

            for (Task task : executableTasks) {
                out.println("▶️ 并行任务 [" + task.getId() + "]: " + task.getDescription());
                task.markStarted();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                buffers.put(task.getId(), baos);
                PrintStream taskOut = new PrintStream(baos, true, StandardCharsets.UTF_8);

                futures.add(executor.submit(() -> {
                    try {
                        return TaskExecutionResult.success(task, executeTask(plan.getGoal(), plan, task, taskOut, cancellationToken));
                    } catch (Exception e) {
                        return TaskExecutionResult.failure(task, e);
                    }
                }));
            }

            List<TaskExecutionResult> results = new ArrayList<>();
            for (Future<TaskExecutionResult> future : futures) {
                try {
                    results.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.add(TaskExecutionResult.failure(executableTasks.get(results.size()), e));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    Exception error = cause instanceof Exception ex ? ex : new RuntimeException(cause);
                    results.add(TaskExecutionResult.failure(executableTasks.get(results.size()), error));
                }
            }

            for (Task task : executableTasks) {
                ByteArrayOutputStream buf = buffers.get(task.getId());
                if (buf != null && buf.size() > 0) {
                    out.print(buf.toString(StandardCharsets.UTF_8));
                    out.flush();
                }
            }

            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private String executeTask(String goal, ExecutionPlan plan, Task task,
                               PrintStream taskOut, CancellationToken cancellationToken) throws IOException {
        if (llmClient == null) {
            return "已执行任务 [" + task.getId() + "]: " + task.getDescription();
        }

        String prompt = """
                ## Mode: Plan Task Executor

                你是 Plan-and-Execute 中的任务执行专家。请根据当前任务和上下文，选择合适的工具或生成回复。

                当前任务类型：%s
                任务描述：%s

                如果是 ANALYSIS 或 VERIFICATION 类型任务，且上下文已经足够，请直接输出分析结果，不需要调用工具。
                """.formatted(task.getType(), task.getDescription());

        String taskInput = buildTaskContext(goal, plan, task);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(prompt));
        messages.add(ChatMessage.user(taskInput));

        StringBuilder allResults = new StringBuilder();
        int iteration = 0;

        while (iteration < MAX_TASK_ITERATIONS) {
            if (cancellationToken != null && cancellationToken.isCancelled()) {
                return "⏹️ 已取消任务 [" + task.getId() + "]。";
            }
            iteration++;

            StringBuilder streamedChunk = new StringBuilder();
            try {
                ChatResponse response = llmClient.stream(messages, toolDefinitions, delta -> {
                    if (delta != null) {
                        streamedChunk.append(delta);
                        taskOut.print(delta);
                        taskOut.flush();
                    }
                }, cancellationToken);

                if (!response.hasToolCalls()) {
                    String content = response.content();
                    if ((content == null || content.isBlank()) && !streamedChunk.isEmpty()) {
                        content = streamedChunk.toString();
                    }
                    if (content == null || content.isBlank()) {
                        return allResults.toString().trim();
                    }
                    return content.trim();
                }

                taskOut.println("\n🔧 执行工具调用: " + response.toolCalls().size() + " 个");
                messages.add(ChatMessage.assistant(response.content(), response.toolCalls()));

                for (ToolCall call : response.toolCalls()) {
                    if (cancellationToken != null && cancellationToken.isCancelled()) {
                        return "⏹️ 已取消任务 [" + task.getId() + "]。";
                    }
                    if (toolExecutor != null) {
                        ToolResult toolResult = toolExecutor.execute(call, cancellationToken);
                        String observation = toolResult.observationJson(mapper);
                        allResults.append(toolResult.summary()).append("\n");
                        messages.add(ChatMessage.tool(call.id(), observation));
                    } else {
                        messages.add(ChatMessage.tool(call.id(), "{\"status\":\"error\",\"summary\":\"Tool executor not configured\"}"));
                    }
                }
            } catch (Exception e) {
                throw new IOException("任务 [" + task.getId() + "] 执行异常: " + e.getMessage(), e);
            }
        }

        return allResults.toString().trim();
    }

    private String buildTaskContext(String goal, ExecutionPlan plan, Task task) {
        StringBuilder context = new StringBuilder();
        context.append("总目标：").append(goal).append("\n");
        context.append("当前任务：").append(task.getDescription()).append("\n");

        if (task.getDependencies().isEmpty()) {
            context.append("依赖任务：无\n");
        } else {
            context.append("依赖任务结果：\n");
            for (String depId : task.getDependencies()) {
                Task dep = plan.getTask(depId);
                if (dep != null) {
                    context.append("- ").append(dep.getId())
                            .append(" / ").append(dep.getDescription())
                            .append(" / 状态=").append(dep.getStatus())
                            .append("\n");
                    if (dep.getResult() != null && !dep.getResult().isBlank()) {
                        context.append(dep.getResult()).append("\n");
                    }
                }
            }
        }

        context.append("请执行此任务。如果是 ANALYSIS 或 VERIFICATION 类型，请基于以上上下文直接给出结果。");
        return context.toString();
    }

    private String buildFinalResult(ExecutionPlan plan) {
        StringBuilder result = new StringBuilder();
        List<Task> leafTasks = plan.getAllTasks().stream()
                .filter(task -> task.getDependents().isEmpty())
                .toList();

        for (Task task : leafTasks) {
            if (task.getResult() != null && !task.getResult().isBlank()) {
                if (!result.isEmpty()) {
                    result.append("\n");
                }
                result.append("[").append(task.getId()).append("] ").append(task.getResult());
            }
        }

        if (!result.isEmpty()) {
            return result.toString();
        }

        return plan.getAllTasks().stream()
                .filter(task -> task.getResult() != null && !task.getResult().isBlank())
                .reduce((first, second) -> second)
                .map(Task::getResult)
                .orElse("");
    }
}
