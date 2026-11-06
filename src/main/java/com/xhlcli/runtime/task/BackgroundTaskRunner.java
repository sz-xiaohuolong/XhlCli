package com.xhlcli.runtime.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.agent.*;
import com.xhlcli.config.SecretRedactor;
import com.xhlcli.config.StreamingSecretRedactor;
import com.xhlcli.context.ContextAssembler;
import com.xhlcli.context.TokenBudget;
import com.xhlcli.hitl.*;
import com.xhlcli.llm.*;
import com.xhlcli.memory.MemoryManager;
import com.xhlcli.model.*;
import com.xhlcli.policy.*;
import com.xhlcli.prompt.*;
import com.xhlcli.skill.SkillRegistry;
import com.xhlcli.skill.builtin.BuiltinSkills;
import com.xhlcli.skill.tool.LoadSkillTool;
import com.xhlcli.tool.*;
import com.xhlcli.tool.demo.*;
import com.xhlcli.tool.local.*;
import com.xhlcli.tool.local.search.*;
import com.xhlcli.web.tool.*;
import java.nio.file.*;
import java.time.*;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** 每项任务重新装配只读 Agent，不共享前台历史、审批和可变工具状态。 */
public final class BackgroundTaskRunner implements ManagedTaskRunner {
    private final Supplier<LlmClient> clients;
    private final Path userHome;
    private final RunLimits limits;
    private final int concurrency;
    private final Duration toolTimeout;
    private final String apiKey;

    public BackgroundTaskRunner(Supplier<LlmClient> clients, Path userHome, RunLimits limits,
                                int concurrency, Duration toolTimeout, String apiKey) {
        this.clients = Objects.requireNonNull(clients);
        this.userHome = Objects.requireNonNull(userHome);
        this.limits = Objects.requireNonNull(limits);
        this.concurrency = concurrency;
        this.toolTimeout = Objects.requireNonNull(toolTimeout);
        this.apiKey = apiKey;
    }

    @Override public TaskOutcome run(TaskExecution execution) throws Exception {
        if (execution.cancellation().isCancelled()) return new TaskOutcome(TaskStatus.CANCELED, "", "用户取消");
        Path workspace = execution.workspace().toRealPath();
        LlmClient client = new BoundedClient(Objects.requireNonNull(clients.get(), "模型未配置"));
        ObjectMapper mapper = new ObjectMapper();
        WorkspacePathResolver paths = new WorkspacePathResolver(workspace);
        SkillRegistry skills = new SkillRegistry(userHome.resolve(".xhlcli/skills"), workspace.resolve(".xhlcli/skills"), BuiltinSkills.all());
        skills.scanAndReload();
        ToolRegistry registry = new ToolRegistry(List.of(
                new ListDirTool(paths), new ReadFileTool(paths), new GlobFilesTool(paths),
                new GrepCodeTool(paths, new JavaCodeSearchEngine()), new SearchCodeTool(paths),
                new EchoTool(), new CurrentTimeTool(Clock.systemUTC()),
                new WebSearchTool(), new WebFetchTool(), new LoadSkillTool(skills)));
        DefaultToolExecutor delegate = new DefaultToolExecutor(registry, new ToolSchemaValidator(mapper),
                new ToolResultBudget(ToolResultBudget.DEFAULT_MAX_CHARS, mapper), mapper, System::nanoTime,
                new PathGuard(workspace), new BackgroundApproval(), new AuditLog(userHome.resolve(".xhlcli/audit")));
        ProgressOutput progress = new ProgressOutput(execution);
        try (var scheduler = new ScheduledTimeoutScheduler();
             var executor = new TaskToolExecutor(delegate, execution.cancellation(), toolTimeout, scheduler)) {
            ReactAgent agent = new ReactAgent(ChatMessage.system(systemPrompt(workspace, client, skills)), client,
                    executor, registry.definitions(), limits, scheduler, mapper, Clock.systemUTC(), execution::id,
                    text -> SecretRedactor.redact(text, apiKey), () -> new StreamingSecretRedactor(apiKey));
            agent.setProjectDirectory(workspace);
            agent.setContextAssembler(new ContextAssembler(new TokenBudget(client.capabilities().maxContextWindow())));
            MemoryManager memory = new MemoryManager(userHome.resolve(".xhlcli/memory"), workspace.resolve(".xhlcli/memory"));
            var memorySnapshot = List.copyOf(memory.loadAll());
            agent.setMemorySupplier(() -> memorySnapshot);
            agent.setConcurrencyLimits(concurrency, toolTimeout);
            RunResult result = agent.run(execution.prompt(), progress::accept, execution.cancellation());
            progress.flush();
            TaskOutcome outcome = TaskOutcome.from(result);
            if (executor.denied() && outcome.status() == TaskStatus.COMPLETED) {
                return new TaskOutcome(TaskStatus.FAILED, "", "后台只读任务请求了需审批的操作，请改在前台执行");
            }
            return new TaskOutcome(outcome.status(), SecretRedactor.redact(outcome.result(), apiKey),
                    SecretRedactor.redact(outcome.error() + (progress.failure.isBlank() ? "" : "：" + progress.failure), apiKey));
        }
    }

    private String systemPrompt(Path workspace, LlmClient client, SkillRegistry skills) throws Exception {
        var assembler = new LayeredPromptAssembler();
        assembler.registerBlock(PromptLayer.RUNTIME_CONTEXT, PromptSource.BUILTIN, "## 后台任务环境",
                "工作区：" + workspace + "\n模型：" + client.modelName() + "\n日期：" + LocalDate.now()
                + "\n这是独立的后台只读任务，没有前台对话历史。只使用已提供的只读工具。"
                + "不要写文件、执行 Shell、操作浏览器或调用 MCP；需要这些操作时明确说明限制，不声称已完成。");
        assembler.registerBlock(PromptLayer.SKILL_INDEX, PromptSource.BUILTIN, "## 可用技能", skills.generateIndexPrompt(2500));
        Path rules = workspace.resolve(".xhlcli/rules.md");
        if (Files.isRegularFile(rules)) {
            Path safeRules = new WorkspacePathResolver(workspace).resolveSafe(".xhlcli/rules.md");
            assembler.registerBlock(PromptLayer.PROJECT_RULES_AND_MEMORY, PromptSource.PROJECT, "## 项目规则", Files.readString(safeRules));
        }
        return assembler.assembleSystemPrompt();
    }

    /** 后台不另发摘要请求；每轮使用同一个执行取消令牌，并在调用前校验估算预算。 */
    private static final class BoundedClient implements LlmClient {
        private final LlmClient delegate;
        private BoundedClient(LlmClient delegate) { this.delegate = delegate; }
        @Override public String providerName() { return delegate.providerName(); }
        @Override public String modelName() { return delegate.modelName(); }
        @Override public ModelCapabilities capabilities() { return delegate.capabilities(); }
        @Override public ChatResponse stream(List<ChatMessage> messages, List<ToolDefinition> tools,
                                              StreamListener listener, CancellationToken token) throws LlmException {
            if (token.isCancelled()) throw new LlmException(LlmErrorType.CANCELLED, "后台任务已取消", false, false);
            int window = capabilities().maxContextWindow();
            long estimate = TokenBudget.estimateTokens(messages);
            for (ToolDefinition tool : tools) {
                estimate += TokenBudget.estimateTokens(tool.name()) + TokenBudget.estimateTokens(tool.description())
                        + TokenBudget.estimateTokens(tool.parameters().toString()) + 16;
            }
            if (estimate + Math.min(2000, window / 4) > window) {
                throw new LlmException(LlmErrorType.INVALID_CONFIGURATION,
                        "后台任务上下文超过估算预算，请缩小任务范围后重新提交；后台暂不自动摘要", false, false);
            }
            return delegate.stream(messages, tools, listener, token);
        }
    }

    private final class ProgressOutput {
        private final TaskExecution execution;
        private final StringBuilder output = new StringBuilder();
        private long lastPublished;
        private String failure = "";
        private ProgressOutput(TaskExecution execution) { this.execution = execution; }
        synchronized void accept(RunEvent event) {
            if (event instanceof RunEvent.RunFailed failed) failure = failed.safeMessage();
            if (event instanceof RunEvent.TextDelta delta && output.length() < 65536) {
                output.append(delta.text(), 0, Math.min(delta.text().length(), 65536 - output.length()));
                if (System.nanoTime() - lastPublished >= Duration.ofMillis(250).toNanos()) flush();
            }
        }
        synchronized void flush() {
            if (!output.isEmpty()) execution.progress().accept(SecretRedactor.redact(output.toString(), apiKey));
            lastPublished = System.nanoTime();
        }
    }

    private static final class BackgroundApproval implements HitlHandler {
        @Override public ApprovalResult requestApproval(ApprovalRequest request) {
            return ApprovalResult.reject("后台任务无交互审批，请在前台执行");
        }
        @Override public boolean isEnabled() { return true; }
        @Override public void setEnabled(boolean enabled) {
            if (!enabled) throw new IllegalStateException("不能关闭后台权限保护");
        }
    }
}
