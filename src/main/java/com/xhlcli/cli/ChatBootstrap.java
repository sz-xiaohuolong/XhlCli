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
import com.xhlcli.tool.DefaultToolExecutor;
import com.xhlcli.tool.ToolRegistry;
import com.xhlcli.tool.ToolResultBudget;
import com.xhlcli.tool.ToolSchemaValidator;
import com.xhlcli.tool.demo.CurrentTimeTool;
import com.xhlcli.tool.demo.EchoTool;

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
            ToolRegistry registry = new ToolRegistry(List.of(new EchoTool(), new CurrentTimeTool(Clock.systemUTC())));
            DefaultToolExecutor executor = new DefaultToolExecutor(
                    registry, new ToolSchemaValidator(mapper), new ToolResultBudget(ToolResultBudget.DEFAULT_MAX_CHARS, mapper),
                    mapper, System::nanoTime);
            ReactAgent agent = new ReactAgent(
                    ChatMessage.system("You are XhlCLI, a helpful coding assistant. You may use only the provided demo tools."),
                    client, executor, registry.definitions(),
                    new RunLimits(config.agentSettings().maxIterations(), config.agentSettings().timeout()), scheduler,
                    mapper, Clock.systemUTC(), () -> UUID.randomUUID().toString(),
                    value -> SecretRedactor.redact(value, config.apiKey()),
                    () -> new StreamingSecretRedactor(config.apiKey()));
            PlainRunRenderer renderer = new PlainRunRenderer(out, err, config.apiKey());
            ChatLoop loop = new ChatLoop(terminal, new ChatCommandParser(), agent, renderer, config);
            terminal.bind(loop);
            return loop.run();
        } catch (IOException failure) {
            err.println("Unable to initialize the terminal: " + failure.getMessage());
            return 3;
        }
    }
}
