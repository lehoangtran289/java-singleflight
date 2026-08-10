package io.singleflight.model;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InFlightCallTest {

    @Test
    void markSharedRecordsDuplicate() {
        InFlightCall<String> call = new InFlightCall<>();

        assertFalse(call.isShared());
        call.markShared();

        assertTrue(call.isShared());
    }

    @Test
    void executeSyncReturnsValueRunsCleanupAndSignalsWaiters() {
        InFlightCall<String> call = new InFlightCall<>();
        AtomicInteger cleanupCount = new AtomicInteger();
        CompletableFuture<SingleFlightResult<String>> waiter = call.resultFuture();

        SingleFlightResult<String> result = call.executeSync(() -> "value", cleanupCount::incrementAndGet);

        assertEquals("value", result.value());
        assertNull(result.exception());
        assertSame(result, waiter.join());
        assertEquals(1, cleanupCount.get());
    }

    @Test
    void executeSyncCapturesException() {
        InFlightCall<String> call = new InFlightCall<>();
        RuntimeException failure = new RuntimeException("boom");

        SingleFlightResult<String> result = call.executeSync(() -> {
            throw failure;
        }, () -> { });

        assertNull(result.value());
        assertSame(failure, result.exception());
    }

    @Test
    void executeAsyncSignalsResultAndRunsCleanup() {
        InFlightCall<String> call = new InFlightCall<>();
        AtomicInteger cleanupCount = new AtomicInteger();
        CompletableFuture<SingleFlightResult<String>> waiter = call.resultFuture();

        call.executeAsync(() -> "value", cleanupCount::incrementAndGet);

        assertEquals("value", waiter.join().value());
        assertEquals(1, cleanupCount.get());
    }

    @Test
    void resultFutureReturnsIndependentCopies() {
        InFlightCall<String> call = new InFlightCall<>();
        CompletableFuture<SingleFlightResult<String>> first = call.resultFuture();
        CompletableFuture<SingleFlightResult<String>> second = call.resultFuture();

        assertNotSame(first, second);
        assertTrue(first.cancel(true));
        call.signalWaiters(new SingleFlightResult<>("value", null));

        assertTrue(first.isCancelled());
        assertEquals("value", second.join().value());
    }

    @Test
    void executeSyncSignalsWaitersWhenCleanupFails() {
        InFlightCall<String> call = new InFlightCall<>();
        CompletableFuture<SingleFlightResult<String>> waiter = call.resultFuture();
        IllegalStateException cleanupFailure = new IllegalStateException("cleanup failed");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> call.executeSync(() -> "value", () -> {
                    throw cleanupFailure;
                }));

        assertSame(cleanupFailure, thrown);
        assertEquals("value", waiter.join().value());
    }

    @Test
    void executeAsyncSignalsWaitersWhenCleanupFails() {
        InFlightCall<String> call = new InFlightCall<>();
        CompletableFuture<SingleFlightResult<String>> waiter = call.resultFuture();
        IllegalStateException cleanupFailure = new IllegalStateException("cleanup failed");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> call.executeAsync(() -> "value", () -> {
                    throw cleanupFailure;
                }));

        assertSame(cleanupFailure, thrown);
        assertEquals("value", waiter.join().value());
    }
}
