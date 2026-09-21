package com.xhlcli.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.agent.ReactAgent;
import com.xhlcli.agent.RunLimits;
import com.xhlcli.agent.ScheduledTimeoutScheduler;
import com.xhlcli.config.ChatConfig;
import com.xhlcli.config.ChatConfigLoader;
import com.xhlcli.config.ConfigurationException;
import com.xhlcli.config.SecretRedactor;
import com.xhlcli.config.StreamingSecretRedactor;
import com.xhlcli.llm.DeepSeekClient;
import com.xhlcli.model.ChatMessage;
import com.xhlcli.render.PlainRunRenderer;
import com.xhlcli.render.PlainDiagnosticSink;
import com.xhlcli.hitl.TerminalHitlHandler;
import com.xhlcli.policy.AuditLog;
import com.xhlcli.policy.PathGuard;
import com.xhlcli.tool.DefaultToolExecutor;
import com.xhlcli.tool.ToolRegistry;
import com.xhlcli.tool.ToolResultBudget;
import com.xhlcli.tool.ToolSchemaValidator;
import com.xhlcli.tool.demo.CurrentTimeTool;
import com.xhlcli.tool.demo.EchoTool;
import com.xhlcli.tool.local.ApplyPatchTool;
import com.xhlcli.tool.local.ExecuteCommandTool;
import com.xhlcli.tool.local.GitDiffTool;
import com.xhlcli.tool.local.GlobFilesTool;
import com.xhlcli.tool.local.ListDirTool;
import com.xhlcli.tool.local.ReadFileTool;
import com.xhlcli.tool.local.WorkspacePathResolver;
import com.xhlcli.tool.local.WriteFileTool;
import com.xhlcli.tool.local.search.GrepCodeTool;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class ChatBootstrap implements ChatRunner {
    private final Map<String, String> environment;
    private final Path projectDirectory;
    private final Path userHome;
    private final PrintStream out;
    private final PrintStream err;

    public ChatBootstrap(
            Map<String, String> environment,
            Path projectDirectory,
            Path userHome,
            PrintStream out,
            PrintStream err) {
        this.environment = Map.copyOf(environment);
        this.projectDirectory = Objects.requireNonNull(projectDirectory, "projectDirectory");
        this.userHome = Objects.requireNonNull(userHome, "userHome");
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
    }

    @Override
    public int run(String[] args) {
        final ChatConfig config;
        try {
            config = ChatConfigLoader.load(args, environment, projectDirectory, userHome);
        } catch (ConfigurationException failure) {
            err.println("Configuration error: " + failure.getMessage());
            return 3;
        }
        if (!config.hasApiKey()) {
            err.println("DeepSeek API Key is missing.");
            err.println("Copy .env.example to .env and set DEEPSEEK_API_KEY, then run XhlCLI again.");
            return 3;
        }

        PlainDiagnosticSink diagnostics = new PlainDiagnosticSink(config, err);
        try (DeepSeekClient client = new DeepSeekClient(config, diagnostics);
             ScheduledTimeoutScheduler scheduler = new ScheduledTimeoutScheduler();
             JLineTerminalSession terminal = new JLineTerminalSession()) {
            ObjectMapper mapper = new ObjectMapper();
            WorkspacePathResolver pathResolver = new WorkspacePathResolver(projectDirectory);
            PathGuard pathGuard = new PathGuard(projectDirectory);
            AuditLog auditLog = new AuditLog(userHome.resolve(".xhlcli").resolve("audit"));
            TerminalHitlHandler hitlHandler = new TerminalHitlHandler(true);

            GrepCodeTool grepCodeTool = new GrepCodeTool(pathResolver);
            com.xhlcli.tool.local.search.SearchCodeTool searchCodeTool = new com.xhlcli.tool.local.search.SearchCodeTool(pathResolver);
            ToolRegistry registry = new ToolRegistry(List.of(
                    new ListDirTool(pathResolver),
                    new ReadFileTool(pathResolver),
                    new WriteFileTool(pathResolver),
                    new ApplyPatchTool(pathResolver),
                    new GitDiffTool(pathResolver),
                    new ExecuteCommandTool(pathResolver),
                    new GlobFilesTool(pathResolver),
                    grepCodeTool,
                    searchCodeTool,
                    new EchoTool(),
                    new CurrentTimeTool(Clock.systemUTC())));
            DefaultToolExecutor executor = new DefaultToolExecutor(
                    registry, new ToolSchemaValidator(mapper), new ToolResultBudget(ToolResultBudget.DEFAULT_MAX_CHARS, mapper),
                    mapper, System::nanoTime, pathGuard, hitlHandler, auditLog);
            String systemPrompt = """
                    You are XhlCLI, a helpful and precise coding assistant.
                    Please reply in Chinese (中文).

                    ## Code Exploration Pipeline
                    0. `search_code`: RAG 语义辅助检索代码库，根据自然语言意图查找可能相关的代码块与模块入口。
                    1. `glob_files`: Locate candidate filenames or structural patterns (e.g. `**/*Service.java`).
                    2. `grep_code`: Locate exact symbols, method declarations, configurations, or lines.
                    3. `read_file`: Read bounded line ranges around matches using suggested `offset` and `limit`. Never read the whole file if nearby lines suffice.
                    4. When `grep_code` indicates `partial: true`, refine your search with a more specific `path`, `glob`, or `pattern`.

                    ## Local Code First Rule
                    - When the user asks about the current repository, code, architecture, or configuration, ALWAYS use local exploration tools (`search_code`, `glob_files`, `grep_code`, `read_file`).
                    - NEVER fabricate file paths or line numbers. Every code claim must cite real relative paths and line numbers verified from tool results.
                    - NEVER invoke external web searches for questions about current local code.

                    ## Modification Guidelines
                    - Use `write_file` to create or overwrite files.
                    - Use `apply_patch` for precise single-occurrence text replacements in existing files.
                    - Use `git_diff` to check unstaged changes in the repository.
                    - Use `execute_command` to run short-running build, test, and shell commands in the project directory.
                    - All file operations are restricted to the project workspace.
                    """;
            ReactAgent agent = new ReactAgent(
                    ChatMessage.system(systemPrompt),
                    client, executor, registry.definitions(),
                    new RunLimits(config.agentSettings().maxIterations(), config.agentSettings().timeout()), scheduler,
                    mapper, Clock.systemUTC(), () -> UUID.randomUUID().toString(),
                    value -> SecretRedactor.redact(value, config.apiKey()),
                    () -> new StreamingSecretRedactor(config.apiKey()));
            Map<String, String> dotEnv = Map.of();
            try {
                dotEnv = ChatConfigLoader.readDotEnv(projectDirectory.resolve(".env"));
            } catch (Exception ignored) {}
            Map<String, String> mergedEnv = new java.util.HashMap<>(dotEnv);
            mergedEnv.putAll(environment);
            com.xhlcli.llm.LlmProviderRegistry providerRegistry = new com.xhlcli.llm.LlmProviderRegistry(mergedEnv);

            Path globalMemoryDir = userHome.resolve(".xhlcli").resolve("memory");
            Path projectMemoryDir = projectDirectory.resolve(".xhlcli").resolve("memory");
            com.xhlcli.memory.MemoryManager memoryManager = new com.xhlcli.memory.MemoryManager(globalMemoryDir, projectMemoryDir);
            com.xhlcli.context.TokenBudget budget = new com.xhlcli.context.TokenBudget(client.capabilities().maxContextWindow());
            com.xhlcli.context.ContextAssembler contextAssembler = new com.xhlcli.context.ContextAssembler(budget);
            com.xhlcli.memory.ConversationHistoryCompactor compactor = new com.xhlcli.memory.ConversationHistoryCompactor(client);
            agent.setContextAssembler(contextAssembler);
            agent.setCompactor(compactor);
            agent.setMemorySupplier(memoryManager::loadAll);
            agent.setConcurrencyLimits(config.agentSettings().maxConcurrency(), config.agentSettings().toolTimeout());
            PlainRunRenderer renderer = new PlainRunRenderer(out, err, config.apiKey());
            ChatLoop loop = new ChatLoop(terminal, new ChatCommandParser(), agent, renderer, config, hitlHandler);
            loop.setMemoryManager(memoryManager);
            loop.setContextAssembler(contextAssembler);
            loop.setCompactor(compactor);
            loop.setGrepCodeTool(grepCodeTool);
            loop.setProjectDirectory(projectDirectory);
            loop.setLlmClient(client);
            loop.setProviderRegistry(providerRegistry);
            loop.setDiagnostics(diagnostics);

            com.xhlcli.agent.PlanExecuteAgent.PlanReviewHandler reviewHandler = (goal, plan) -> {
                out.println(plan.summarize());
                out.println("📝 计划已生成。");
                out.println("   - 回车 / y / run：按当前计划执行");
                out.println("   - cancel / esc：取消本次计划");
                out.println("   - 输入文本：补充要求后重新规划\n");
                try {
                    String reviewInput = terminal.readLine("审阅 > ");
                    com.xhlcli.cli.PlanReviewInputParser.Decision decision =
                            com.xhlcli.cli.PlanReviewInputParser.parse(reviewInput);
                    return switch (decision.type()) {
                        case EXECUTE -> com.xhlcli.agent.PlanExecuteAgent.PlanReviewDecision.execute();
                        case CANCEL -> com.xhlcli.agent.PlanExecuteAgent.PlanReviewDecision.cancel();
                        case SUPPLEMENT -> com.xhlcli.agent.PlanExecuteAgent.PlanReviewDecision.supplement(decision.feedback());
                    };
                } catch (Exception e) {
                    return com.xhlcli.agent.PlanExecuteAgent.PlanReviewDecision.cancel();
                }
            };
            com.xhlcli.agent.PlanExecuteAgent planAgent = new com.xhlcli.agent.PlanExecuteAgent(
                    client, executor, registry.definitions(), null, memoryManager, reviewHandler, out);
            loop.setPlanAgent(planAgent);

            com.xhlcli.team.TeamOrchestrator teamOrchestrator = new com.xhlcli.team.TeamOrchestrator(
                    client, executor, registry.definitions(), memoryManager, out);
            loop.setTeamOrchestrator(teamOrchestrator);

            // MCP Extension initialization
            Path userMcpFile = userHome.resolve(".xhlcli").resolve("mcp.json");
            Path projectMcpFile = projectDirectory.resolve(".xhlcli").resolve("mcp.json");
            com.xhlcli.mcp.config.McpConfigLoader mcpLoader = new com.xhlcli.mcp.config.McpConfigLoader(mapper);
            com.xhlcli.mcp.config.McpConfigLoader.LoadResult mcpLoadResult = mcpLoader.load(userMcpFile, projectMcpFile, mergedEnv);

            com.xhlcli.mcp.manager.McpServerManager mcpManager = new com.xhlcli.mcp.manager.McpServerManager(registry, projectDirectory, mapper);
            mcpManager.registerServers(mcpLoadResult);
            mcpManager.addToolsUpdatedListener(defs -> {
                agent.setToolDefinitions(defs);
                planAgent.setToolDefinitions(defs);
                teamOrchestrator.setToolDefinitions(defs);
            });
            mcpManager.startAll(java.time.Duration.ofMillis(3000));
            loop.setMcpServerManager(mcpManager);

            terminal.bind(loop);
            int exitCode = loop.run();
            mcpManager.close();
            return exitCode;
        } catch (IOException failure) {
            err.println("Unable to initialize the terminal: " + failure.getMessage());
            return 3;
        }
    }
}
