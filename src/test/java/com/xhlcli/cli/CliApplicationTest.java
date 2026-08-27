package com.xhlcli.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CliApplicationTest {
    private ByteArrayOutputStream stdout;
    private ByteArrayOutputStream stderr;
    private CliApplication application;
    private AtomicInteger chatRuns;

    @BeforeEach
    void setUp() {
        stdout = new ByteArrayOutputStream();
        stderr = new ByteArrayOutputStream();
        chatRuns = new AtomicInteger();
        application = new CliApplication(
                "0.1.0-test",
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                args -> {
                    chatRuns.incrementAndGet();
                    return 0;
                });
    }

    @Test
    void noArgumentsStartsInjectedChatRunner() {
        assertEquals(0, application.run(new String[0]));
        assertEquals(1, chatRuns.get());
        assertEquals("", out());
        assertEquals("", err());
    }

    @Test
    void helpShowsAgentOptionsAndNeverAnApiKeyOption() {
        assertEquals(0, application.run(new String[] {"--help"}));
        assertTrue(out().contains("--help"));
        assertTrue(out().contains("--version"));
        assertTrue(out().contains("--model"));
        assertTrue(out().contains("--max-iterations"));
        assertTrue(out().contains("--agent-timeout"));
        assertTrue(out().contains("/clear"));
        assertFalse(out().contains("--api-key"));
        assertEquals(0, chatRuns.get());
    }

    @Test
    void versionUsesInjectedBuildVersion() {
        assertEquals(0, application.run(new String[] {"--version"}));
        assertEquals("XhlCLI 0.1.0-test\n", out());
    }

    @Test
    void unknownArgumentReturnsUsageError() {
        assertEquals(2, application.run(new String[] {"--unknown"}));
        assertTrue(err().contains("Unknown option: --unknown"));
        assertTrue(err().contains("--help"));
        assertEquals(0, chatRuns.get());
    }

    @Test
    void returnsBootstrapConfigurationExitCodeAndSafeGuidance() {
        application = new CliApplication(
                "0.1.0-test",
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                args -> {
                    stderr.writeBytes(("DeepSeek API Key is missing.\n"
                            + "Copy .env.example to .env and set DEEPSEEK_API_KEY, then run XhlCLI again.\n")
                            .getBytes(StandardCharsets.UTF_8));
                    return 3;
                });

        assertEquals(3, application.run(new String[0]));
        assertTrue(err().contains("DEEPSEEK_API_KEY"));
        assertFalse(err().contains("sk-"));
    }

    private String out() {
        return stdout.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return stderr.toString(StandardCharsets.UTF_8);
    }
}
