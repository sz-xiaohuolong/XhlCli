package com.xhlcli.agent;

import com.xhlcli.model.RunStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunLifecycleTest {

    @Test
    void allowsTheReactActiveStateTransitionsAndFinishesOnlyOnce() {
        RunLifecycle lifecycle = new RunLifecycle();

        lifecycle.transitionTo(RunStatus.THINKING);
        lifecycle.transitionTo(RunStatus.CALLING_TOOL);
        lifecycle.transitionTo(RunStatus.OBSERVING);

        assertTrue(lifecycle.finish(RunStatus.COMPLETED));
        assertFalse(lifecycle.finish(RunStatus.FAILED));
        assertEquals(RunStatus.COMPLETED, lifecycle.status());
        assertThrows(IllegalStateException.class, lifecycle::requireActive);
    }

    @Test
    void rejectsIllegalTransitionsAndTerminalTransitionsOutsideFinish() {
        RunLifecycle lifecycle = new RunLifecycle();

        assertThrows(IllegalStateException.class, () -> lifecycle.transitionTo(RunStatus.OBSERVING));
        assertThrows(IllegalArgumentException.class, () -> lifecycle.transitionTo(RunStatus.COMPLETED));
        assertThrows(IllegalArgumentException.class, () -> lifecycle.finish(RunStatus.THINKING));
    }

    @Test
    void rejectsLimitsOutsideThePhaseTwoRange() {
        assertThrows(IllegalArgumentException.class, () -> new RunLimits(0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new RunLimits(101, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new RunLimits(1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new RunLimits(1, Duration.ofHours(1).plusSeconds(1)));
    }
}
