package com.xhlcli.cli;

import com.xhlcli.app.ChatSession;
import com.xhlcli.config.ChatConfig;
import com.xhlcli.config.ChatConfigLoader;
import com.xhlcli.config.ConfigurationException;
import com.xhlcli.llm.DeepSeekClient;
import com.xhlcli.render.PlainChatRenderer;
import com.xhlcli.render.PlainDiagnosticSink;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

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
             JLineTerminalSession terminal = new JLineTerminalSession()) {
            ChatSession session = new ChatSession(client);
            PlainChatRenderer renderer = new PlainChatRenderer(out, err, config.apiKey());
            ChatLoop loop = new ChatLoop(terminal, new ChatCommandParser(), session, renderer, config);
            terminal.bind(loop);
            return loop.run();
        } catch (IOException failure) {
            err.println("Unable to initialize the terminal: " + failure.getMessage());
            return 3;
        }
    }
}
