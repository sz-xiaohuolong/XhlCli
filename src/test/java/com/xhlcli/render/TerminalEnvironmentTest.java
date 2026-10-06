package com.xhlcli.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TerminalEnvironment Tests")
class TerminalEnvironmentTest {

    @Test
    @DisplayName("Should detect NO_COLOR environment variable and disable ANSI")
    void shouldDetectNoColor() {
        TerminalEnvironment env = TerminalEnvironment.detect(
                new String[]{},
                Map.of("NO_COLOR", "1", "TERM", "xterm-256color"),
                null
        );

        assertTrue(env.isNoColor());
        assertFalse(env.isAnsiSupported());
    }

    @Test
    @DisplayName("Should detect empty NO_COLOR as disabled")
    void shouldDetectEmptyNoColor() {
        TerminalEnvironment env = TerminalEnvironment.detect(
                new String[]{},
                Map.of("NO_COLOR", "", "TERM", "xterm-256color"),
                null
        );

        assertFalse(env.isNoColor());
        assertTrue(env.isAnsiSupported());
    }

    @Test
    @DisplayName("Should detect --plain command line argument and disable ANSI")
    void shouldDetectPlainArgument() {
        TerminalEnvironment env = TerminalEnvironment.detect(
                new String[]{"--plain"},
                Map.of("TERM", "xterm-256color"),
                null
        );

        assertTrue(env.isPlainRequested());
        assertFalse(env.isAnsiSupported());
        assertFalse(env.isInteractive());
    }

    @Test
    @DisplayName("Should detect dumb terminal as non-ANSI and non-interactive")
    void shouldDetectDumbTerminal() {
        TerminalEnvironment env = TerminalEnvironment.detect(
                new String[]{},
                Map.of("TERM", "dumb"),
                null
        );

        assertTrue(env.isDumbTerminal());
        assertFalse(env.isAnsiSupported());
        assertFalse(env.isInteractive());
    }

    @Test
    @DisplayName("Should support ANSI when interactive terminal and no disable flags")
    void shouldSupportAnsiWhenNormalTerminal() {
        TerminalEnvironment env = new TerminalEnvironment(false, false, "xterm-256color", true);

        assertFalse(env.isNoColor());
        assertFalse(env.isPlainRequested());
        assertFalse(env.isDumbTerminal());
        assertTrue(env.isAnsiSupported());
        assertTrue(env.isInteractive());
    }
}
