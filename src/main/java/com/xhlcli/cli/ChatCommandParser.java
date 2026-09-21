package com.xhlcli.cli;

import java.util.Locale;
import java.util.Objects;

public final class ChatCommandParser {
    public ChatCommand parse(String input) {
        String normalized = Objects.requireNonNull(input, "input").trim();
        if (!normalized.startsWith("/")) {
            return ChatCommand.USER_MESSAGE;
        }
        String cmd = normalized.split("\\s+")[0].toLowerCase(Locale.ROOT);
        return switch (cmd) {
            case "/help" -> ChatCommand.HELP;
            case "/config" -> ChatCommand.CONFIG;
            case "/clear" -> ChatCommand.CLEAR;
            case "/exit", "/quit" -> ChatCommand.EXIT;
            case "/context" -> ChatCommand.CONTEXT;
            case "/compact" -> ChatCommand.COMPACT;
            case "/save" -> ChatCommand.SAVE;
            case "/memory" -> ChatCommand.MEMORY;
            case "/search-text" -> ChatCommand.SEARCH_TEXT;
            case "/index" -> ChatCommand.INDEX;
            case "/search" -> ChatCommand.SEARCH;
            case "/plan" -> ChatCommand.PLAN;
            case "/team" -> ChatCommand.TEAM;
            case "/model" -> ChatCommand.MODEL;
            case "/mcp" -> ChatCommand.MCP;
            default -> ChatCommand.UNKNOWN;
        };
    }
}
