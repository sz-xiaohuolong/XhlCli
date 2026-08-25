package com.xhlcli.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CliApplicationTest {
    private ByteArrayOutputStream stdout;
    private ByteArrayOutputStream stderr;
    private CliApplication application;

    @BeforeEach
    void setUp() {
        stdout = new ByteArrayOutputStream();
        stderr = new ByteArrayOutputStream();
        application = new CliApplication(
                "0.1.0-test",
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
    }

    @Test
    void noArgumentsShowsProductAndNextStep() {
        assertEquals(0, application.run(new String[0]));
        assertTrue(out().contains("XhlCLI"));
        assertTrue(out().contains("--help"));
        assertEquals("", err());
    }

    @Test
    void helpShowsOnlyDeliveredPhaseZeroOptions() {
        assertEquals(0, application.run(new String[] {"--help"}));
        assertTrue(out().contains("--help"));
        assertTrue(out().contains("--version"));
        assertFalse(out().contains("agent"));
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
    }

    private String out() {
        return stdout.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return stderr.toString(StandardCharsets.UTF_8);
    }
}
