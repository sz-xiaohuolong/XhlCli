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
        assertEquals(ChatCommand.CONTEXT, parser.parse("/context"));
        assertEquals(ChatCommand.COMPACT, parser.parse("/compact"));
        assertEquals(ChatCommand.SAVE, parser.parse("/save some text"));
        assertEquals(ChatCommand.MEMORY, parser.parse("/memory list"));
        assertEquals(ChatCommand.SEARCH_TEXT, parser.parse("/search-text foo"));
        assertEquals(ChatCommand.SEARCH_TEXT, parser.parse("/search bar"));
    }

    @Test
    void distinguishesMessagesAndUnknownSlashCommands() {
        assertEquals(ChatCommand.USER_MESSAGE, parser.parse("explain /help"));
        assertEquals(ChatCommand.USER_MESSAGE, parser.parse("  "));
        assertEquals(ChatCommand.UNKNOWN, parser.parse("/tools"));
    }
}
