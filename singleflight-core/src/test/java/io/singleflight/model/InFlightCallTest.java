package io.singleflight.model;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InFlightCallTest {

    @Test
    void executeSyncReturnsValueRunsCleanupAndSignalsWaiters() {
        InFlightCall<String> call = new InFlightCall<>();
        AtomicInteger cleanupCount = new AtomicInteger();
        CompletableFuture<String> waiter = call.resultFuture();

        String result = call.executeSync(() -> "value", cleanupCount::incrementAndGet);

        assertEquals("value", result);
        assertSame(result, waiter.join());
        assertEquals(1, cleanupCount.get());
    }

    @Test
    void executeSyncRunsCleanupAndCompletesExceptionallyWhenSupplierThrows() {
        InFlightCall<String> call = new InFlightCall<>();
        AtomicInteger cleanupCount = new AtomicInteger();
        CompletableFuture<String> waiter = call.resultFuture();
        RuntimeException failure = new RuntimeException("boom");

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> call.executeSync(() -> {
            throw failure;
        }, cleanupCount::incrementAndGet));

        assertSame(failure, thrown);
        assertEquals(1, cleanupCount.get());
        ExecutionException waiterFailure = assertThrows(ExecutionException.class, waiter::get);
        assertSame(failure, waiterFailure.getCause());
    }

    @Test
    void executeAsyncSignalsResultAndRunsCleanup() {
        InFlightCall<String> call = new InFlightCall<>();
        AtomicInteger cleanupCount = new AtomicInteger();
        CompletableFuture<String> waiter = call.resultFuture();

        call.executeAsync(() -> "value", cleanupCount::incrementAndGet);

        assertEquals("value", waiter.join());
        assertEquals(1, cleanupCount.get());
    }

    @Test
    void resultFutureReturnsIndependentCopies() {
        InFlightCall<String> call = new InFlightCall<>();
        CompletableFuture<String> first = call.resultFuture();
        CompletableFuture<String> second = call.resultFuture();

        assertNotSame(first, second);
        assertTrue(first.cancel(true));
        call.complete("value");

        assertTrue(first.isCancelled());
        assertEquals("value", second.join());
    }

    @Test
    void executeAsyncRunsCleanupAndCompletesExceptionallyWhenSupplierThrows() {
        InFlightCall<String> call = new InFlightCall<>();
        AtomicInteger cleanupCount = new AtomicInteger();
        CompletableFuture<String> waiter = call.resultFuture();
        RuntimeException failure = new RuntimeException("boom");

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> call.executeAsync(() -> {
            throw failure;
        }, cleanupCount::incrementAndGet));

        assertSame(failure, thrown);
        assertEquals(1, cleanupCount.get());
        ExecutionException waiterFailure = assertThrows(ExecutionException.class, waiter::get);
        assertSame(failure, waiterFailure.getCause());
    }

    @Test
    void awaitPropagatesExecutionExceptionFromFuture() {
        InFlightCall<String> call = new InFlightCall<>();
        RuntimeException failure = new RuntimeException("boom");

        call.completeExceptionally(failure);

        ExecutionException thrown = assertThrows(ExecutionException.class, call::await);
        assertSame(failure, thrown.getCause());
    }
}
