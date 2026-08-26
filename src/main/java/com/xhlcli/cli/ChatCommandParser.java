package com.xhlcli.cli;

import java.util.Locale;
import java.util.Objects;

public final class ChatCommandParser {
    public ChatCommand parse(String input) {
        String normalized = Objects.requireNonNull(input, "input").trim();
        if (!normalized.startsWith("/")) {
            return ChatCommand.USER_MESSAGE;
        }
        return switch (normalized.toLowerCase(Locale.ROOT)) {
            case "/help" -> ChatCommand.HELP;
            case "/config" -> ChatCommand.CONFIG;
            case "/clear" -> ChatCommand.CLEAR;
            case "/exit", "/quit" -> ChatCommand.EXIT;
            default -> ChatCommand.UNKNOWN;
        };
    }
}
