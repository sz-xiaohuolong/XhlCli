package com.xhlcli.llm;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancellationTokenTest {

    @Test
    void cancellationIsIdempotentAndInvokesEachCallbackOnce() {
        CancellationToken token = new CancellationToken();
        AtomicInteger calls = new AtomicInteger();
        CancellationToken.Registration registration = token.onCancel(calls::incrementAndGet);

        token.cancel();
        token.cancel();
        registration.close();

        assertTrue(token.isCancelled());
        assertEquals(1, calls.get());
    }

    @Test
    void registrationAfterCancellationRunsImmediately() {
        CancellationToken token = new CancellationToken();
        AtomicInteger calls = new AtomicInteger();
        token.cancel();

        token.onCancel(calls::incrementAndGet).close();

        assertEquals(1, calls.get());
    }

    @Test
    void closingRegistrationBeforeCancellationDeregistersCallback() {
        CancellationToken token = new CancellationToken();
        AtomicInteger calls = new AtomicInteger();
        CancellationToken.Registration registration = token.onCancel(calls::incrementAndGet);

        registration.close();
        token.cancel();

        assertEquals(0, calls.get());
    }

    @Test
    void failingCallbackDoesNotPreventRemainingCallbacks() {
        CancellationToken token = new CancellationToken();
        AtomicInteger calls = new AtomicInteger();
        token.onCancel(() -> { throw new IllegalStateException("boom"); });
        token.onCancel(calls::incrementAndGet);

        token.cancel();

        assertEquals(1, calls.get());
    }
}
