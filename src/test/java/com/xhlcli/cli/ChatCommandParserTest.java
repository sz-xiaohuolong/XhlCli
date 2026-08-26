package com.xhlcli.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatCommandParserTest {
    private final ChatCommandParser parser = new ChatCommandParser();

    @Test
    void recognizesCommandsCaseInsensitively() {
        assertEquals(ChatCommand.HELP, parser.parse("/help"));
        assertEquals(ChatCommand.CONFIG, parser.parse(" /config "));
        assertEquals(ChatCommand.CLEAR, parser.parse("/CLEAR"));
        assertEquals(ChatCommand.EXIT, parser.parse("/exit"));
    }

    @Test
    void distinguishesMessagesAndUnknownSlashCommands() {
        assertEquals(ChatCommand.USER_MESSAGE, parser.parse("explain /help"));
        assertEquals(ChatCommand.USER_MESSAGE, parser.parse("  "));
        assertEquals(ChatCommand.UNKNOWN, parser.parse("/tools"));
    }
}
