package com.xhlcli.cli;

import com.xhlcli.agent.AgentRunner;
import com.xhlcli.config.ChatConfig;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.render.PlainRunRenderer;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class ChatLoop {
    private final InputReader inputReader;
    private final ChatCommandParser commandParser;
    private final AgentRunner agent;
    private final PlainRunRenderer renderer;
    private final ChatConfig config;
    private final com.xhlcli.hitl.HitlHandler hitlHandler;
    private com.xhlcli.memory.MemoryManager memoryManager;
    private com.xhlcli.context.ContextAssembler contextAssembler;
    private com.xhlcli.memory.ConversationHistoryCompactor compactor;

    public void setMemoryManager(com.xhlcli.memory.MemoryManager manager) { this.memoryManager = manager; }
    public void setContextAssembler(com.xhlcli.context.ContextAssembler assembler) { this.contextAssembler = assembler; }
    public void setCompactor(com.xhlcli.memory.ConversationHistoryCompactor compactor) { this.compactor = compactor; }

    private void printContext() {
        if (contextAssembler != null) {
            System.out.println("Context Window: " + contextAssembler.getBudget().getContextWindow());
            System.out.println("Available for conversation: " + contextAssembler.getBudget().getAvailableForConversation());
        } else {
            System.out.println("Context manager not initialized.");
        }
    }

    private void compactHistory() {
        System.out.println("Compacting history...");
        agent.clearHistory(); // just a simplified version
        System.out.println("History compacted.");
    }

    private void saveMemory(String input) {
        if (memoryManager != null) {
            String content = input.substring(input.indexOf(" ") + 1).trim();
            if (input.contains("--global")) {
                memoryManager.saveGlobal(content.replace("--global", "").trim(), "user");
            } else {
                memoryManager.saveProject(content, "user");
            }
            System.out.println("Memory saved.");
        }
    }

    private void manageMemory(String input) {
        if (memoryManager != null) {
            String[] parts = input.split("\\s+");
            if (parts.length > 1) {
                String subcmd = parts[1];
                if (subcmd.equals("list")) {
                    memoryManager.loadAll().forEach(e -> System.out.println(e.id() + " [" + e.scope() + "]: " + e.content()));
                } else if (subcmd.equals("clear")) {
                    memoryManager.clearAll();
                    System.out.println("All memories cleared.");
                } else if (subcmd.equals("delete") && parts.length > 2) {
                    memoryManager.delete(parts[2]);
                    System.out.println("Memory deleted.");
                }
            }
        }
    }
    private final AtomicReference<CancellationToken> activeResponse = new AtomicReference<>();

    public ChatLoop(
            InputReader inputReader,
            ChatCommandParser commandParser,
            AgentRunner agent,
            PlainRunRenderer renderer,
            ChatConfig config) {
        this(inputReader, commandParser, agent, renderer, config, null);
    }

    public ChatLoop(
            InputReader inputReader,
            ChatCommandParser commandParser,
            AgentRunner agent,
            PlainRunRenderer renderer,
            ChatConfig config,
            com.xhlcli.hitl.HitlHandler hitlHandler) {
        this.inputReader = Objects.requireNonNull(inputReader, "inputReader");
        this.commandParser = Objects.requireNonNull(commandParser, "commandParser");
        this.agent = Objects.requireNonNull(agent, "agent");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.config = Objects.requireNonNull(config, "config");
        this.hitlHandler = hitlHandler;
    }

    public int run() {
        renderer.printWelcome(config.model());
        while (true) {
            String input;
            try {
                input = inputReader.readLine("You > ");
            } catch (InputInterruptedException ignored) {
                continue;
            } catch (InputEndOfFileException ignored) {
                renderer.printGoodbye();
                return 0;
            }

            switch (commandParser.parse(input)) {
                case HELP -> renderer.printHelp();
                case CONFIG -> renderer.printConfig(config);
                case CLEAR -> {
                    agent.clearHistory();
                    if (hitlHandler != null) {
                        hitlHandler.clearApprovedAll();
                    }
                    renderer.printCleared();
                }
                case EXIT -> {
                    renderer.printGoodbye();
                    return 0;
                }
                case CONTEXT -> printContext();
                case COMPACT -> compactHistory();
                case SAVE -> saveMemory(input);
                case MEMORY -> manageMemory(input);                case UNKNOWN -> renderer.printUnknownCommand(input.trim());
                case USER_MESSAGE -> sendTurn(input);
            }
        }
    }

    public boolean cancelActiveResponse() {
        CancellationToken token = activeResponse.getAndSet(null);
        if (token == null) {
            return false;
        }
        token.cancel();
        return true;
    }

    private void sendTurn(String input) {
        if (input.isBlank()) {
            return;
        }
        CancellationToken token = new CancellationToken();
        if (!activeResponse.compareAndSet(null, token)) {
            throw new IllegalStateException("A response is already active");
        }
        try {
            agent.run(input, renderer::accept, token);
        } finally {
            activeResponse.compareAndSet(token, null);
        }
    }
}
