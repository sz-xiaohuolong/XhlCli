package com.xhlcli.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xhlcli.agent.ReactAgent;
import com.xhlcli.agent.RunLimits;
import com.xhlcli.agent.ScheduledTimeoutScheduler;
import com.xhlcli.cli.terminal.SafeHistory;
import com.xhlcli.cli.terminal.TerminalCompleter;
import com.xhlcli.cli.terminal.TerminalInputHighlighter;
import com.xhlcli.config.ChatConfig;
import com.xhlcli.config.ChatConfigLoader;
import com.xhlcli.config.ConfigurationException;
import com.xhlcli.config.SecretRedactor;
import com.xhlcli.config.StreamingSecretRedactor;
import com.xhlcli.llm.DeepSeekClient;
import com.xhlcli.model.ChatMessage;
import com.xhlcli.render.PlainDiagnosticSink;
import com.xhlcli.render.PlainRunRenderer;
import com.xhlcli.render.TerminalEnvironment;
import com.xhlcli.render.TerminalRenderer;
import com.xhlcli.render.terminal.InlineTerminalRenderer;
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
import com.xhlcli.browser.BrowserConnectivityCheck;
import com.xhlcli.browser.BrowserGuard;
import com.xhlcli.browser.BrowserSession;
import com.xhlcli.browser.DefaultBrowserConnector;
import com.xhlcli.browser.SensitivePagePolicy;
import com.xhlcli.browser.tool.BrowserConnectTool;
import com.xhlcli.browser.tool.BrowserDisconnectTool;
import com.xhlcli.browser.tool.BrowserStatusTool;
import com.xhlcli.web.tool.WebFetchTool;
import com.xhlcli.web.tool.WebSearchTool;
import com.xhlcli.prompt.LayeredPromptAssembler;
import com.xhlcli.prompt.PromptLayer;
import com.xhlcli.prompt.PromptSource;
import com.xhlcli.skill.SkillRegistry;
import com.xhlcli.skill.builtin.BuiltinSkills;
import com.xhlcli.skill.tool.LoadSkillTool;
import com.xhlcli.snapshot.SnapshotService;
import com.xhlcli.tool.local.RevertTurnTool;

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
             ScheduledTimeoutScheduler scheduler = new ScheduledTimeoutScheduler()) {
            ObjectMapper mapper = new ObjectMapper();
            WorkspacePathResolver pathResolver = new WorkspacePathResolver(projectDirectory);
            PathGuard pathGuard = new PathGuard(projectDirectory);
            AuditLog auditLog = new AuditLog(userHome.resolve(".xhlcli").resolve("audit"));
            TerminalHitlHandler hitlHandler = new TerminalHitlHandler(true);

            BrowserSession browserSession = new BrowserSession();
            SensitivePagePolicy sensitivePagePolicy = new SensitivePagePolicy(
                    userHome.resolve(".xhlcli").resolve("sensitive_patterns.txt"));
            BrowserGuard browserGuard = new BrowserGuard(browserSession, sensitivePagePolicy);
            BrowserConnectivityCheck connectivityCheck = new BrowserConnectivityCheck();
            DefaultBrowserConnector browserConnector = new DefaultBrowserConnector(
                    browserSession, connectivityCheck, sensitivePagePolicy);

            GrepCodeTool grepCodeTool = new GrepCodeTool(pathResolver);
            com.xhlcli.tool.local.search.SearchCodeTool searchCodeTool = new com.xhlcli.tool.local.search.SearchCodeTool(pathResolver);
            Path userSkillsDir = userHome.resolve(".xhlcli").resolve("skills");
            Path projectSkillsDir = projectDirectory.resolve(".xhlcli").resolve("skills");
            SkillRegistry skillRegistry = new SkillRegistry(userSkillsDir, projectSkillsDir, BuiltinSkills.all());
            skillRegistry.scanAndReload();
            LoadSkillTool loadSkillTool = new LoadSkillTool(skillRegistry);

            SnapshotService snapshotService = SnapshotService.forProject(projectDirectory);
            RevertTurnTool revertTurnTool = new RevertTurnTool(snapshotService);

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
                    new CurrentTimeTool(Clock.systemUTC()),
                    new WebSearchTool(),
                    new WebFetchTool(),
                    new BrowserConnectTool(browserConnector),
                    new BrowserDisconnectTool(browserConnector),
                    new BrowserStatusTool(browserConnector),
                    loadSkillTool,
                    revertTurnTool));
            DefaultToolExecutor executor = new DefaultToolExecutor(
                    registry, new ToolSchemaValidator(mapper), new ToolResultBudget(ToolResultBudget.DEFAULT_MAX_CHARS, mapper),
                    mapper, System::nanoTime, pathGuard, hitlHandler, auditLog, browserGuard);

            LayeredPromptAssembler promptAssembler = new LayeredPromptAssembler();
            promptAssembler.registerBlock(
                    PromptLayer.RUNTIME_CONTEXT,
                    PromptSource.BUILTIN,
                    "## Runtime Context",
                    String.format("- Workspace: %s%n- Model: %s (Context: %dk)%n- Date: %s",
                            projectDirectory.toAbsolutePath(),
                            client.modelName(),
                            client.capabilities().maxContextWindow() / 1000,
                            java.time.LocalDate.now())
            );
            String skillIndexPrompt = skillRegistry.generateIndexPrompt(2500);
            if (!skillIndexPrompt.isBlank()) {
                promptAssembler.registerBlock(
                        PromptLayer.SKILL_INDEX,
                        PromptSource.BUILTIN,
                        "## Available Skills",
                        skillIndexPrompt
                );
            }
            Path projectRulesFile = projectDirectory.resolve(".xhlcli").resolve("rules.md");
            if (java.nio.file.Files.exists(projectRulesFile)) {
                try {
                    String projectRules = java.nio.file.Files.readString(projectRulesFile, java.nio.charset.StandardCharsets.UTF_8);
                    promptAssembler.registerBlock(
                            PromptLayer.PROJECT_RULES_AND_MEMORY,
                            PromptSource.PROJECT,
                            "## Project Rules",
                            projectRules
                    );
                } catch (Exception ignored) {}
            }
            String systemPrompt = promptAssembler.assembleSystemPrompt();
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
            Map<String, String> userEnv = Map.of();
            try {
                userEnv = ChatConfigLoader.readUserEnv(userHome.resolve(".xhlcli").resolve("config.json"));
            } catch (Exception ignored) {}
            Map<String, String> mergedEnv = new java.util.HashMap<>(userEnv);
            mergedEnv.putAll(dotEnv);
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
            agent.setProjectDirectory(projectDirectory);
            agent.setConcurrencyLimits(config.agentSettings().maxConcurrency(), config.agentSettings().toolTimeout());

            // MCP Extension initialization
            Path userMcpFile = userHome.resolve(".xhlcli").resolve("mcp.json");
            Path projectMcpFile = projectDirectory.resolve(".xhlcli").resolve("mcp.json");
            com.xhlcli.mcp.config.McpConfigLoader mcpLoader = new com.xhlcli.mcp.config.McpConfigLoader(mapper);
            com.xhlcli.mcp.config.McpConfigLoader.LoadResult mcpLoadResult = mcpLoader.load(userMcpFile, projectMcpFile, mergedEnv);

            com.xhlcli.mcp.manager.McpServerManager mcpManager = new com.xhlcli.mcp.manager.McpServerManager(registry, projectDirectory, mapper);
            mcpManager.registerServers(mcpLoadResult);
            mcpManager.startAll(java.time.Duration.ofMillis(3000));

            Path historyFile = userHome.resolve(".xhlcli").resolve("history");
            SafeHistory safeHistory = new SafeHistory(historyFile);
            TerminalCompleter completer = new TerminalCompleter(
                    () -> providerRegistry != null ? providerRegistry.listModels().stream().map(com.xhlcli.llm.ModelDescriptor::modelName).toList() : List.of(client.modelName()),
                    () -> mcpManager != null ? List.copyOf(mcpManager.listServers().keySet()) : List.of(),
                    () -> skillRegistry != null ? skillRegistry.listAll().stream().map(com.xhlcli.skill.SkillDefinition::name).toList() : List.of(),
                    projectDirectory
            );
            TerminalInputHighlighter highlighter = new TerminalInputHighlighter();

            try (JLineTerminalSession terminal = new JLineTerminalSession(completer, highlighter, safeHistory)) {
                TerminalEnvironment termEnv = TerminalEnvironment.detect(args, mergedEnv, terminal.terminal());
                boolean isInteractive = termEnv.isInteractive() && termEnv.isAnsiSupported();
                TerminalRenderer renderer = isInteractive
                        ? new InlineTerminalRenderer(terminal.terminal(), terminal.reader(), config.apiKey())
                        : new PlainRunRenderer(out, err, config.apiKey());

                hitlHandler.setInputReader(terminal::readLine);
                hitlHandler.setOutputConsumer(renderer::printMessage);

                ChatLoop loop = new ChatLoop(terminal, new ChatCommandParser(), agent, renderer, config, hitlHandler);
                loop.setSafeHistory(safeHistory);
                loop.setMemoryManager(memoryManager);
                loop.setContextAssembler(contextAssembler);
                loop.setCompactor(compactor);
                loop.setGrepCodeTool(grepCodeTool);
                loop.setProjectDirectory(projectDirectory);
                loop.setLlmClient(client);
                loop.setProviderRegistry(providerRegistry);
                loop.setDiagnostics(diagnostics);
                loop.setBrowserConnector(browserConnector);
                loop.setSkillRegistry(skillRegistry);
                loop.setPromptAssembler(promptAssembler);
                loop.setMcpServerManager(mcpManager);
                loop.setSnapshotService(snapshotService);

                Path taskDbDir = userHome.resolve(".xhlcli").resolve("tasks");
                String customTaskDir = System.getProperty("xhlcli.task.dir", System.getenv("XHLCLI_TASK_DIR"));
                if (customTaskDir != null && !customTaskDir.isBlank()) {
                    taskDbDir = Path.of(customTaskDir);
                }
                Path taskDb = taskDbDir.resolve("tasks.db");
                com.xhlcli.runtime.task.DurableTaskManager durableTaskManager = null;
                try {
                    durableTaskManager = new com.xhlcli.runtime.task.DurableTaskManager(taskDb, prompt -> {
                        com.xhlcli.model.RunResult result = agent.run(prompt, event -> {}, new com.xhlcli.llm.CancellationToken());
                        return result.finalAnswer();
                    });
                    durableTaskManager.start();
                    loop.setDurableTaskManager(durableTaskManager);
                } catch (Exception e) {
                    diagnostics.debug("durable_task_init_failed", Map.of("error", e.getMessage() != null ? e.getMessage() : "unknown"));
                }

                com.xhlcli.agent.PlanExecuteAgent.PlanReviewHandler reviewHandler = (goal, plan) -> {
                    renderer.printMessage(plan.summarize());
                    renderer.printMessage("📝 计划已生成。");
                    renderer.printMessage("   - 回车 / y / run：按当前计划执行");
                    renderer.printMessage("   - cancel / esc：取消本次计划");
                    renderer.printMessage("   - 输入文本：补充要求后重新规划\n");
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

                mcpManager.addToolsUpdatedListener(defs -> {
                    agent.setToolDefinitions(defs);
                    planAgent.setToolDefinitions(defs);
                    teamOrchestrator.setToolDefinitions(defs);
                });

                terminal.bind(loop);
                try {
                    int exitCode = loop.run();
                    return exitCode;
                } finally {
                    try {
                        durableTaskManager.close();
                    } catch (Exception ignored) {}
                    try {
                        mcpManager.close();
                    } catch (Exception ignored) {}
                    try {
                        snapshotService.close();
                    } catch (Exception ignored) {}
                }
            }
        } catch (IOException failure) {
            err.println("Unable to initialize the terminal: " + failure.getMessage());
            return 3;
        }
    }
}
