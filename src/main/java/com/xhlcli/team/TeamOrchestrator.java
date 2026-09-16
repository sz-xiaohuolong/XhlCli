package com.xhlcli.team;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.agent.AgentRunner;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.llm.LlmClient;
import com.xhlcli.memory.MemoryManager;
import com.xhlcli.model.*;
import com.xhlcli.tool.ToolExecutor;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 团队协同编排器（TeamOrchestrator） - Multi-Agent 架构的中枢指挥官。
 *
 * 核心设计原则：
 * 1. 1+2+1 专职角色调度体系（Planner 规划、Worker 池借用执行、Reviewer 质量审查）。
 * 2. 最小化上下文交接包（HandoverPackage）：杜绝全量对话历史交叉污染，按步骤独立传递。
 * 3. 结构化审查与有限修正循环（ReviewResult）：审查不通过打回 Worker 重试，超出最大重试次数熔断。
 * 4. 无依赖步骤受控并发：Worker 池化排他借用，独立内存流隔离日志，批次完成后保序 flush 到终端。
 * 5. 全流程生命周期看板与结果交付。
 */
public class TeamOrchestrator implements AgentRunner {
    private static final Logger log = Logger.getLogger(TeamOrchestrator.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final int DEFAULT_MAX_RETRIES = 2;

    public enum StepStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        SKIPPED
    }

    public record ExecutionStep(
            String id,
            String description,
            String type,
            List<String> dependencies,
            String result,
            StepStatus status,
            int retryCount,
            ReviewResult reviewResult
    ) {
        public static ExecutionStep pending(String id, String description, String type, List<String> dependencies) {
            return new ExecutionStep(id, description, type,
                    dependencies == null ? List.of() : List.copyOf(dependencies),
                    null, StepStatus.PENDING, 0, null);
        }

        public ExecutionStep started() {
            return new ExecutionStep(id, description, type, dependencies, result, StepStatus.RUNNING, retryCount, reviewResult);
        }

        public ExecutionStep withSuccess(String result, ReviewResult reviewResult) {
            return new ExecutionStep(id, description, type, dependencies, result, StepStatus.COMPLETED, retryCount, reviewResult);
        }

        public ExecutionStep withFailure(String result, ReviewResult reviewResult) {
            return new ExecutionStep(id, description, type, dependencies, result, StepStatus.FAILED, retryCount, reviewResult);
        }

        public ExecutionStep withRetry(int newRetryCount) {
            return new ExecutionStep(id, description, type, dependencies, result, StepStatus.RUNNING, newRetryCount, reviewResult);
        }

        public ExecutionStep skipped(String reason) {
            return new ExecutionStep(id, description, type, dependencies, "已跳过: " + reason, StepStatus.SKIPPED, retryCount, reviewResult);
        }
    }

    private final LlmClient llmClient;
    private final ToolExecutor toolExecutor;
    private final List<ToolDefinition> toolDefinitions;
    private final MemoryManager memoryManager;
    private final PrintStream out;
    private final int maxRetriesPerStep;
    private final SubAgent planner;
    private final List<SubAgent> workers;
    private final SubAgent reviewer;
    private final List<ChatMessage> committedHistory = new ArrayList<>();

    public TeamOrchestrator(LlmClient llmClient) {
        this(llmClient, null, List.of(), null, System.out, DEFAULT_MAX_RETRIES);
    }

    public TeamOrchestrator(LlmClient llmClient, ToolExecutor toolExecutor, List<ToolDefinition> toolDefinitions) {
        this(llmClient, toolExecutor, toolDefinitions, null, System.out, DEFAULT_MAX_RETRIES);
    }

    public TeamOrchestrator(
            LlmClient llmClient,
            ToolExecutor toolExecutor,
            List<ToolDefinition> toolDefinitions,
            MemoryManager memoryManager,
            PrintStream out) {
        this(llmClient, toolExecutor, toolDefinitions, memoryManager, out, DEFAULT_MAX_RETRIES);
    }

    public TeamOrchestrator(
            LlmClient llmClient,
            ToolExecutor toolExecutor,
            List<ToolDefinition> toolDefinitions,
            MemoryManager memoryManager,
            PrintStream out,
            int maxRetriesPerStep) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient cannot be null");
        this.toolExecutor = toolExecutor;
        this.toolDefinitions = toolDefinitions == null ? List.of() : List.copyOf(toolDefinitions);
        this.memoryManager = memoryManager;
        this.out = out == null ? System.out : out;
        this.maxRetriesPerStep = Math.max(0, maxRetriesPerStep);

        // 1 个专职规划者（无工具）
        this.planner = new SubAgent("planner", TeamRole.PLANNER, llmClient, null, List.of());
        // 2 个 Worker 池成员（持完整工具权限）
        this.workers = List.of(
                new SubAgent("worker-1", TeamRole.WORKER, llmClient, toolExecutor, this.toolDefinitions),
                new SubAgent("worker-2", TeamRole.WORKER, llmClient, toolExecutor, this.toolDefinitions)
        );
        // 1 个专职审查者（无写工具权限）
        this.reviewer = new SubAgent("reviewer", TeamRole.REVIEWER, llmClient, null, List.of());
    }

    public String run(String userInput, CancellationToken cancellationToken) {
        RunResult result = run(userInput, event -> {}, cancellationToken);
        return result.finalAnswer();
    }

    @Override
    public RunResult run(String input, RunEventSink events, CancellationToken cancellationToken) {
        String runId = "team_run_" + UUID.randomUUID();
        if (input == null || input.isBlank()) {
            return new RunResult(runId, RunStatus.COMPLETED, "", "Empty input", 0, TokenUsage.unknown());
        }

        committedHistory.add(ChatMessage.user(input));
        if (cancellationToken != null && cancellationToken.isCancelled()) {
            return new RunResult(runId, RunStatus.CANCELED, "⏹️ 已取消当前团队任务。", "Cancelled", 0, TokenUsage.unknown());
        }

        // ==========================================
        // 第一阶段：规划阶段 (Planning Phase)
        // ==========================================
        out.println("==================================================");
        out.println("📋 第一阶段：任务规划与依赖拆解");
        out.println("==================================================");
        out.println("🧑‍💼 规划者正在分析任务并构建步骤依赖图...\n");

        TeamMessage planMsg = TeamMessage.task("orchestrator", "请为以下任务制定执行计划：\n" + input);
        TeamMessage planResult = planner.execute(planMsg, out, cancellationToken);
        planner.clearHistory();

        if (cancellationToken != null && cancellationToken.isCancelled()) {
            return new RunResult(runId, RunStatus.CANCELED, "⏹️ 已取消当前团队任务。", "Cancelled", 0, TokenUsage.unknown());
        }
        if (planResult.type() == TeamMessage.Type.ERROR) {
            String errorMsg = "❌ 规划阶段失败：" + planResult.content();
            committedHistory.add(ChatMessage.assistant(errorMsg));
            return new RunResult(runId, RunStatus.FAILED, errorMsg, planResult.content(), 0, TokenUsage.unknown());
        }

        List<ExecutionStep> steps = parsePlan(planResult.content());
        if (steps.isEmpty()) {
            String errorMsg = "❌ 规划失败：未能解析出有效步骤列表。\n原始规划输出:\n" + planResult.content();
            committedHistory.add(ChatMessage.assistant(errorMsg));
            return new RunResult(runId, RunStatus.FAILED, errorMsg, "Invalid plan format", 0, TokenUsage.unknown());
        }

        out.println("\n📋 执行计划列表：");
        for (ExecutionStep step : steps) {
            String deps = step.dependencies().isEmpty() ? "无依赖" : "依赖: " + String.join(", ", step.dependencies());
            out.println("  • [" + step.id() + "] (" + step.type() + ", " + deps + "): " + step.description());
        }
        out.println();

        // ==========================================
        // 第二阶段：执行与审查阶段 (Execution & Review Phase)
        // ==========================================
        out.println("==================================================");
        out.println("⚡ 第二阶段：步骤执行与质量审查");
        out.println("==================================================");

        BlockingQueue<SubAgent> workerPool = new LinkedBlockingQueue<>(workers);
        int singleStepCursor = 0;
        int batchIndex = 0;

        while (true) {
            if (cancellationToken != null && cancellationToken.isCancelled()) {
                return new RunResult(runId, RunStatus.CANCELED, "⏹️ 已取消当前团队任务。", "Cancelled", 0, TokenUsage.unknown());
            }

            List<ExecutionStep> executable = getExecutableSteps(steps);
            if (executable.isEmpty()) {
                break;
            }
            batchIndex++;

            if (executable.size() == 1) {
                // 单步骤串行执行（直连主控制台）
                ExecutionStep step = executable.get(0);
                SubAgent worker = workers.get(singleStepCursor % workers.size());
                singleStepCursor++;
                runStep(step, steps, worker, reviewer, out, cancellationToken);
                worker.clearHistory();
            } else {
                // 多步骤并发执行（内存缓冲隔离 + 保序 flush）
                out.println("⚡ 批次 #" + batchIndex + "：" + executable.size()
                        + " 个独立步骤并行执行（Worker 池并发调度，最多 " + workers.size() + " 并发）\n");
                runBatchParallel(executable, steps, workerPool, out, cancellationToken);
            }
        }

        // 处理残留未被执行的步骤
        for (ExecutionStep step : steps) {
            if (step.status() == StepStatus.PENDING) {
                out.println("⏭️ 步骤 [" + step.id() + "] 因前置依赖未满足或失败被跳过: " + step.description());
                updateStep(steps, step.id(), step.skipped("前置依赖未满足"));
            }
        }

        // ==========================================
        // 第三阶段：汇总与交付阶段 (Summary Phase)
        // ==========================================
        out.println("\n==================================================");
        out.println("🏁 第三阶段：汇总与成果看板");
        out.println("==================================================");

        String finalDashboard = buildFinalDashboard(steps);
        out.println(finalDashboard);

        committedHistory.add(ChatMessage.assistant("[团队协作总结]\n" + finalDashboard));
        if (memoryManager != null) {
            try {
                memoryManager.saveProject("用户目标: " + input + "\n多Agent团队协作结果:\n" + finalDashboard, "team_orchestrator");
            } catch (Exception e) {
                log.log(Level.WARNING, "Failed to persist project memory", e);
            }
        }

        boolean anyFailed = steps.stream().anyMatch(s -> s.status() == StepStatus.FAILED);
        RunStatus status = anyFailed ? RunStatus.FAILED : RunStatus.COMPLETED;
        return new RunResult(runId, status, finalDashboard, anyFailed ? "Some steps failed" : "All steps finished", 1, TokenUsage.unknown());
    }

    private void runStep(
            ExecutionStep step,
            List<ExecutionStep> steps,
            SubAgent worker,
            SubAgent reviewer,
            PrintStream stepOut,
            CancellationToken cancellationToken) {
        updateStep(steps, step.id(), step.started());

        String depContext = buildStepContext(steps, step);
        HandoverPackage handover = HandoverPackage.builder(step.id(), step.description())
                .contextSummary(depContext)
                .acceptanceCriteria("实现步骤要求，代码无缺陷，通过必要验证")
                .build();

        int retries = 0;
        String issuesFeedback = "";

        while (true) {
            if (cancellationToken != null && cancellationToken.isCancelled()) {
                updateStep(steps, step.id(), step.withFailure("用户取消", null));
                stepOut.println("⏹️ 步骤 [" + step.id() + "] 已取消\n");
                return;
            }

            stepOut.println("🛠️ [" + worker.getName() + "] 执行步骤 [" + step.id() + "]: " + step.description());

            TeamMessage taskMsg;
            if (retries == 0) {
                taskMsg = TeamMessage.task("orchestrator", handover.toPromptText());
            } else {
                String retryPrompt = handover.toPromptText()
                        + "\n\n【前次执行审查未通过，请针对以下缺陷重点修复】：\n" + issuesFeedback;
                taskMsg = TeamMessage.task("orchestrator", retryPrompt);
            }

            TeamMessage workResult = worker.execute(taskMsg, stepOut, cancellationToken);
            if (cancellationToken != null && cancellationToken.isCancelled()) {
                updateStep(steps, step.id(), step.withFailure("用户取消", null));
                return;
            }

            if (workResult.type() == TeamMessage.Type.ERROR) {
                updateStep(steps, step.id(), step.withFailure("执行报错: " + workResult.content(), null));
                stepOut.println("❌ 步骤 [" + step.id() + "] 执行失败：" + workResult.content() + "\n");
                return;
            }

            // 审查阶段
            stepOut.println("🔍 [" + reviewer.getName() + "] 正在审查步骤 [" + step.id() + "] 的产出...");
            TeamMessage reviewMsg = reviewer.review(step.description(), workResult.content(), stepOut, cancellationToken);
            reviewer.clearHistory();

            if (cancellationToken != null && cancellationToken.isCancelled()) {
                updateStep(steps, step.id(), step.withFailure("用户取消", null));
                return;
            }

            ReviewResult reviewResult;
            if (reviewMsg.type() == TeamMessage.Type.ERROR) {
                stepOut.println("⚠️ 步骤 [" + step.id() + "] 审查阶段异常，保守判定为未通过\n");
                reviewResult = ReviewResult.changesRequested("审查阶段异常: " + reviewMsg.content(), List.of(reviewMsg.content()), List.of());
            } else {
                reviewResult = ReviewResult.parse(reviewMsg.content());
            }

            if (reviewResult.isApproved()) {
                updateStep(steps, step.id(), step.withSuccess(workResult.content(), reviewResult));
                stepOut.println("✅ 步骤 [" + step.id() + "] 审查通过\n");
                return;
            }

            // 审查未通过，检查重试次数
            if (retries < maxRetriesPerStep) {
                retries++;
                updateStep(steps, step.id(), step.withRetry(retries));
                issuesFeedback = reviewResult.formatIssues();
                stepOut.println("⚠️ 步骤 [" + step.id() + "] 审查未通过（打回重试 " + retries + "/" + maxRetriesPerStep + "）：");
                stepOut.println("   反馈详情:\n" + issuesFeedback + "\n");
                worker.clearHistory(); // 清空 Worker 历史，轻装重试
            } else {
                // 熔断保护
                stepOut.println("❌ 步骤 [" + step.id() + "] 审查多次未通过，达到最大重试次数 (" + maxRetriesPerStep + ")，触发熔断保护！\n");
                updateStep(steps, step.id(), step.withFailure("审查未通过: " + reviewResult.summary(), reviewResult));
                return;
            }
        }
    }

    private void runBatchParallel(
            List<ExecutionStep> batch,
            List<ExecutionStep> steps,
            BlockingQueue<SubAgent> workerPool,
            PrintStream mainOut,
            CancellationToken cancellationToken) {
        int parallelism = Math.min(batch.size(), workers.size());
        ExecutorService executor = Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r, "xhlcli-multi-agent-worker");
            t.setDaemon(true);
            return t;
        });

        Map<String, ByteArrayOutputStream> buffers = new ConcurrentHashMap<>();
        List<Future<?>> futures = new ArrayList<>();

        for (ExecutionStep step : batch) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            buffers.put(step.id(), baos);
            PrintStream stepOut = new PrintStream(baos, true, StandardCharsets.UTF_8);

            futures.add(executor.submit(() -> {
                SubAgent worker = null;
                // 每个并发步骤分配专用的临时审查者，避免并发上下文竞争
                SubAgent localReviewer = new SubAgent("reviewer-" + step.id(), TeamRole.REVIEWER, llmClient, null, List.of());
                try {
                    worker = workerPool.take(); // 排他借用 Worker
                    runStep(step, steps, worker, localReviewer, stepOut, cancellationToken);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    updateStep(steps, step.id(), step.withFailure("并行执行被中断", null));
                    stepOut.println("❌ 步骤 [" + step.id() + "] 被中断\n");
                } catch (Exception e) {
                    log.log(Level.SEVERE, "Parallel step " + step.id() + " failed", e);
                    updateStep(steps, step.id(), step.withFailure("并行执行异常: " + e.getMessage(), null));
                    stepOut.println("❌ 步骤 [" + step.id() + "] 并行执行异常：" + e.getMessage() + "\n");
                } finally {
                    if (worker != null) {
                        worker.clearHistory();
                        workerPool.offer(worker); // 归还 Worker 池
                    }
                    stepOut.flush();
                }
            }));
        }

        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                log.log(Level.WARNING, "Batch execution future wait failed", e);
            }
        }
        executor.shutdownNow();

        // 批次完成后，按原始步骤顺序保序 flush 到终端，杜绝日志穿插
        for (ExecutionStep step : batch) {
            ByteArrayOutputStream buf = buffers.get(step.id());
            if (buf != null && buf.size() > 0) {
                mainOut.print(buf.toString(StandardCharsets.UTF_8));
                mainOut.flush();
            }
        }
    }

    private synchronized void updateStep(List<ExecutionStep> steps, String stepId, ExecutionStep updated) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).id().equals(stepId)) {
                steps.set(i, updated);
                return;
            }
        }
    }

    List<ExecutionStep> getExecutableSteps(List<ExecutionStep> steps) {
        Map<String, StepStatus> statusMap = new HashMap<>();
        for (ExecutionStep step : steps) {
            statusMap.put(step.id(), step.status());
        }

        return steps.stream()
                .filter(step -> step.status() == StepStatus.PENDING)
                .filter(step -> step.dependencies().stream()
                        .allMatch(dep -> statusMap.get(dep) == StepStatus.COMPLETED))
                .toList();
    }

    private String buildStepContext(List<ExecutionStep> steps, ExecutionStep currentStep) {
        if (currentStep.dependencies().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String depId : currentStep.dependencies()) {
            for (ExecutionStep s : steps) {
                if (s.id().equals(depId)) {
                    sb.append("【依赖步骤 ").append(depId).append("】").append(s.description())
                            .append("\n产出结论：\n").append(s.result() != null ? s.result().trim() : "无明确产出")
                            .append("\n\n");
                }
            }
        }
        return sb.toString().trim();
    }

    List<ExecutionStep> parsePlan(String planContent) {
        if (planContent == null || planContent.isBlank()) {
            return List.of();
        }
        try {
            String cleaned = planContent.trim();
            if (cleaned.startsWith("```json")) {
                cleaned = cleaned.substring(7);
            } else if (cleaned.startsWith("```")) {
                cleaned = cleaned.substring(3);
            }
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3);
            }
            cleaned = cleaned.trim();

            JsonNode root = MAPPER.readTree(cleaned);
            JsonNode stepsNode = root.path("steps");
            if (!stepsNode.isArray() || stepsNode.isEmpty()) {
                if (root.isArray() && !root.isEmpty()) {
                    stepsNode = root;
                } else {
                    return List.of();
                }
            }

            List<ExecutionStep> steps = new ArrayList<>();
            int seq = 1;
            for (JsonNode stepNode : stepsNode) {
                String id = stepNode.path("id").asText("step_" + seq);
                String description = stepNode.path("description").asText("");
                String type = stepNode.path("type").asText("GENERAL");
                List<String> dependencies = new ArrayList<>();
                JsonNode depsNode = stepNode.path("dependencies");
                if (depsNode.isArray()) {
                    for (JsonNode dep : depsNode) {
                        String depStr = dep.asText("").trim();
                        if (!depStr.isBlank()) {
                            dependencies.add(depStr);
                        }
                    }
                }
                if (!description.isBlank()) {
                    steps.add(ExecutionStep.pending(id, description, type, dependencies));
                    seq++;
                }
            }
            return steps;
        } catch (Exception e) {
            log.log(Level.WARNING, "Failed to parse plan JSON: " + e.getMessage(), e);
            return List.of();
        }
    }

    private String buildFinalDashboard(List<ExecutionStep> steps) {
        StringBuilder sb = new StringBuilder();
        sb.append("### 🎯 Multi-Agent 协同执行看板\n\n");
        sb.append("| 步骤 ID | 类型 | 状态 | 重试次数 | 审查结论 | 描述 |\n");
        sb.append("| :--- | :--- | :--- | :---: | :--- | :--- |\n");

        for (ExecutionStep step : steps) {
            String statusIcon = switch (step.status()) {
                case COMPLETED -> "✅ 成功";
                case FAILED -> "❌ 失败";
                case SKIPPED -> "⏭️ 跳过";
                case RUNNING -> "⚡ 进行中";
                case PENDING -> "⏳ 等待";
            };
            String reviewText = step.reviewResult() != null && step.reviewResult().isApproved()
                    ? "通过"
                    : (step.reviewResult() != null ? "打回(" + step.reviewResult().summary() + ")" : "-");

            sb.append("| ").append(step.id())
                    .append(" | ").append(step.type())
                    .append(" | ").append(statusIcon)
                    .append(" | ").append(step.retryCount())
                    .append(" | ").append(reviewText)
                    .append(" | ").append(step.description())
                    .append(" |\n");
        }

        sb.append("\n**执行成果明细**：\n");
        for (ExecutionStep step : steps) {
            if (step.status() == StepStatus.COMPLETED && step.result() != null) {
                sb.append("\n#### 📌 [").append(step.id()).append("] ").append(step.description()).append("\n");
                sb.append(step.result().trim()).append("\n");
            }
        }
        return sb.toString();
    }

    @Override
    public void clearHistory() {
        committedHistory.clear();
        planner.clearHistory();
        for (SubAgent worker : workers) {
            worker.clearHistory();
        }
        reviewer.clearHistory();
    }

    @Override
    public List<ChatMessage> history() {
        return Collections.unmodifiableList(committedHistory);
    }

    public List<SubAgent> getWorkers() {
        return Collections.unmodifiableList(workers);
    }

    public SubAgent getPlanner() {
        return planner;
    }

    public SubAgent getReviewer() {
        return reviewer;
    }
}
