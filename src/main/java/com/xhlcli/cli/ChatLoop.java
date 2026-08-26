package com.xhlcli.cli;

import com.xhlcli.app.ChatSession;
import com.xhlcli.config.ChatConfig;
import com.xhlcli.llm.CancellationToken;
import com.xhlcli.render.PlainChatRenderer;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class ChatLoop {
    private final InputReader inputReader;
    private final ChatCommandParser commandParser;
    private final ChatSession session;
    private final PlainChatRenderer renderer;
    private final ChatConfig config;
    private final AtomicReference<CancellationToken> activeResponse = new AtomicReference<>();

    public ChatLoop(
            InputReader inputReader,
            ChatCommandParser commandParser,
            ChatSession session,
            PlainChatRenderer renderer,
            ChatConfig config) {
        this.inputReader = Objects.requireNonNull(inputReader, "inputReader");
        this.commandParser = Objects.requireNonNull(commandParser, "commandParser");
        this.session = Objects.requireNonNull(session, "session");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.config = Objects.requireNonNull(config, "config");
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
                    session.clear();
                    renderer.printCleared();
                }
                case EXIT -> {
                    renderer.printGoodbye();
                    return 0;
                }
                case UNKNOWN -> renderer.printUnknownCommand(input.trim());
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
            session.send(input, renderer::accept, token);
        } finally {
            activeResponse.compareAndSet(token, null);
        }
    }
}
