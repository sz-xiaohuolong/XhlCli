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

            ToolRegistry registry = new ToolRegistry(List.of(
                    new ListDirTool(pathResolver),
                    new ReadFileTool(pathResolver),
                    new WriteFileTool(pathResolver),
                    new ApplyPatchTool(pathResolver),
                    new GitDiffTool(pathResolver),
                    new ExecuteCommandTool(pathResolver),
                    new GlobFilesTool(pathResolver),
                    new GrepCodeTool(pathResolver),
                    new EchoTool(),
                    new CurrentTimeTool(Clock.systemUTC())));
            DefaultToolExecutor executor = new DefaultToolExecutor(
                    registry, new ToolSchemaValidator(mapper), new ToolResultBudget(ToolResultBudget.DEFAULT_MAX_CHARS, mapper),
                    mapper, System::nanoTime, pathGuard, hitlHandler, auditLog);
            String systemPrompt = """
                    You are XhlCLI, a helpful and precise coding assistant.
                    Please reply in Chinese (中文).

                    ## Tools Guidelines
                    - Use `glob_files` and `grep_code` to search for files and locate symbols before reading.
                    - Use `read_file` with offset/limit when reading large files.
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
            Path globalMemoryDir = userHome.resolve(".xhlcli").resolve("memory");
            Path projectMemoryDir = projectDirectory.resolve(".xhlcli").resolve("memory");
            com.xhlcli.memory.MemoryManager memoryManager = new com.xhlcli.memory.MemoryManager(globalMemoryDir, projectMemoryDir);
            com.xhlcli.context.TokenBudget budget = new com.xhlcli.context.TokenBudget(100000);
            com.xhlcli.context.ContextAssembler contextAssembler = new com.xhlcli.context.ContextAssembler(budget);
            com.xhlcli.memory.ConversationHistoryCompactor compactor = new com.xhlcli.memory.ConversationHistoryCompactor(client);
            agent.setContextAssembler(contextAssembler);
            agent.setCompactor(compactor);
            agent.setMemorySupplier(memoryManager::loadAll);
            PlainRunRenderer renderer = new PlainRunRenderer(out, err, config.apiKey());
            ChatLoop loop = new ChatLoop(terminal, new ChatCommandParser(), agent, renderer, config, hitlHandler);
            loop.setMemoryManager(memoryManager);
            loop.setContextAssembler(contextAssembler);
            loop.setCompactor(compactor);
            terminal.bind(loop);
            return loop.run();
        } catch (IOException failure) {
            err.println("Unable to initialize the terminal: " + failure.getMessage());
            return 3;
        }
    }
}
