package com.xhlcli.agent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeoutSchedulerTest {

    @Test
    void schedulesActionsAndRejectsNewWorkAfterClose() throws Exception {
        CountDownLatch fired = new CountDownLatch(1);
        ScheduledTimeoutScheduler scheduler = new ScheduledTimeoutScheduler();
        try {
            scheduler.schedule(Duration.ofMillis(1), fired::countDown);

            assertTrue(fired.await(1, TimeUnit.SECONDS));
        } finally {
            scheduler.close();
        }

        assertThrows(IllegalStateException.class,
                () -> scheduler.schedule(Duration.ofMillis(1), () -> { }));
    }

    @Test
    void closingARegistrationPreventsTheScheduledAction() throws Exception {
        CountDownLatch fired = new CountDownLatch(1);
        try (ScheduledTimeoutScheduler scheduler = new ScheduledTimeoutScheduler()) {
            TimeoutScheduler.Registration registration = scheduler.schedule(Duration.ofSeconds(1), fired::countDown);
            registration.close();

            assertFalse(fired.await(50, TimeUnit.MILLISECONDS));
        }
    }
}
