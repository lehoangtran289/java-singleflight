package io.singleflight.core;

import io.singleflight.model.SingleFlightResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Timeout(5)
class DefaultSingleFlightTest {

    private final List<ExecutorService> executors = new ArrayList<>();

    @AfterEach
    void shutDownExecutors() {
        executors.forEach(ExecutorService::shutdownNow);
    }

    @Test
    void executeReturnsSupplierValue() {
        DefaultSingleFlight<String> singleFlight = new DefaultSingleFlight<>(Runnable::run);

        SingleFlightResult<String> result = singleFlight.execute("key", () -> "value");

        assertEquals("value", result.value());
        assertNull(result.exception());
    }

    @Test
    void executeAllowsBlankKeysAndNullValues() {
        DefaultSingleFlight<String> singleFlight = new DefaultSingleFlight<>(Runnable::run);

        SingleFlightResult<String> result = singleFlight.execute("", () -> null);

        assertNull(result.value());
        assertNull(result.exception());
    }

    @Test
    void executeCapturesSupplierFailure() {
        DefaultSingleFlight<String> singleFlight = new DefaultSingleFlight<>(Runnable::run);
        IllegalStateException failure = new IllegalStateException("boom");

        SingleFlightResult<String> result = singleFlight.execute("key", () -> {
            throw failure;
        });

        assertNull(result.value());
        assertSame(failure, result.exception());
    }

    @Test
    void executeRunsAgainAfterPreviousCallCompletes() {
        DefaultSingleFlight<Integer> singleFlight = new DefaultSingleFlight<>(Runnable::run);
        AtomicInteger invocationCount = new AtomicInteger();

        SingleFlightResult<Integer> first = singleFlight.execute("key", invocationCount::incrementAndGet);
        SingleFlightResult<Integer> second = singleFlight.execute("key", invocationCount::incrementAndGet);

        assertEquals(1, first.value());
        assertEquals(2, second.value());
        assertEquals(2, invocationCount.get());
    }

    @Test
    void executeCoalescesConcurrentCallsForSameKey() throws Exception {
        DefaultSingleFlight<String> singleFlight = new DefaultSingleFlight<>(Runnable::run);
        ExecutorService callers = newExecutor(2);
        CountDownLatch supplierStarted = new CountDownLatch(1);
        CountDownLatch releaseSupplier = new CountDownLatch(1);
        CountDownLatch followerStarted = new CountDownLatch(1);
        AtomicInteger invocationCount = new AtomicInteger();

        Future<SingleFlightResult<String>> leader = callers.submit(() -> singleFlight.execute("key", () -> {
            invocationCount.incrementAndGet();
            supplierStarted.countDown();
            await(releaseSupplier);
            return "shared-value";
        }));

        assertTrue(supplierStarted.await(1, SECONDS));
        Future<SingleFlightResult<String>> follower = callers.submit(() -> {
            followerStarted.countDown();
            return singleFlight.execute("key", () -> {
                invocationCount.incrementAndGet();
                return "unexpected";
            });
        });

        try {
            assertTrue(followerStarted.await(1, SECONDS));
            assertThrows(TimeoutException.class, () -> follower.get(100, MILLISECONDS));
        } finally {
            releaseSupplier.countDown();
        }

        assertEquals("shared-value", leader.get().value());
        assertEquals("shared-value", follower.get().value());
        assertEquals(1, invocationCount.get());
    }

    @Test
    void executeDoesNotCoalesceDifferentKeys() {
        ExecutorService supplierExecutor = newExecutor(2);
        DefaultSingleFlight<String> singleFlight = new DefaultSingleFlight<>(supplierExecutor);
        CountDownLatch bothSuppliersStarted = new CountDownLatch(2);

        CompletableFuture<SingleFlightResult<String>> first = singleFlight.executeAsync("first", () -> {
            bothSuppliersStarted.countDown();
            await(bothSuppliersStarted);
            return "first-value";
        });
        CompletableFuture<SingleFlightResult<String>> second = singleFlight.executeAsync("second", () -> {
            bothSuppliersStarted.countDown();
            await(bothSuppliersStarted);
            return "second-value";
        });

        assertEquals("first-value", first.join().value());
        assertEquals("second-value", second.join().value());
    }

    @Test
    void executeAsyncReturnsSupplierValue() {
        DefaultSingleFlight<String> singleFlight = new DefaultSingleFlight<>(Runnable::run);

        SingleFlightResult<String> result = singleFlight.executeAsync("key", () -> "value").join();

        assertEquals("value", result.value());
        assertNull(result.exception());
    }

    @Test
    void executeAsyncCapturesSupplierException() {
        DefaultSingleFlight<String> singleFlight = new DefaultSingleFlight<>(Runnable::run);
        IllegalStateException failure = new IllegalStateException("boom");

        SingleFlightResult<String> result = singleFlight.executeAsync("key", () -> {
            throw failure;
        }).join();

        assertNull(result.value());
        assertSame(failure, result.exception());
    }

    @Test
    void executeAsyncReportsExecutorRejectionAsResultFailure() {
        RejectedExecutionException rejection = new RejectedExecutionException("rejected");
        DefaultSingleFlight<String> singleFlight = new DefaultSingleFlight<>(command -> {
            throw rejection;
        });

        SingleFlightResult<String> result = singleFlight.executeAsync("key", () -> "unused").join();

        assertNull(result.value());
        assertSame(rejection, result.exception());
    }

    @Test
    void executeAsyncCoalescesConcurrentCallsAndReturnsIndependentFutures() throws Exception {
        ExecutorService supplierExecutor = newExecutor(1);
        DefaultSingleFlight<String> singleFlight = new DefaultSingleFlight<>(supplierExecutor);
        CountDownLatch supplierStarted = new CountDownLatch(1);
        CountDownLatch releaseSupplier = new CountDownLatch(1);
        AtomicInteger invocationCount = new AtomicInteger();

        CompletableFuture<SingleFlightResult<String>> leader = singleFlight.executeAsync("key", () -> {
            invocationCount.incrementAndGet();
            supplierStarted.countDown();
            await(releaseSupplier);
            return "shared-value";
        });

        assertTrue(supplierStarted.await(1, SECONDS));
        CompletableFuture<SingleFlightResult<String>> follower = singleFlight.executeAsync("key", () -> {
            invocationCount.incrementAndGet();
            return "unexpected";
        });

        assertNotSame(leader, follower);
        releaseSupplier.countDown();

        assertEquals("shared-value", leader.join().value());
        assertEquals("shared-value", follower.join().value());
        assertEquals(1, invocationCount.get());
    }

    @Test
    void cancellingOneAsyncFutureDoesNotCancelOtherCallers() throws Exception {
        ExecutorService supplierExecutor = newExecutor(1);
        DefaultSingleFlight<String> singleFlight = new DefaultSingleFlight<>(supplierExecutor);
        CountDownLatch supplierStarted = new CountDownLatch(1);
        CountDownLatch releaseSupplier = new CountDownLatch(1);

        CompletableFuture<SingleFlightResult<String>> first = singleFlight.executeAsync("key", () -> {
            supplierStarted.countDown();
            await(releaseSupplier);
            return "value";
        });

        assertTrue(supplierStarted.await(1, SECONDS));
        CompletableFuture<SingleFlightResult<String>> second = singleFlight.executeAsync("key", () -> "unexpected");

        assertTrue(first.cancel(true));
        releaseSupplier.countDown();

        assertTrue(first.isCancelled());
        assertEquals("value", second.join().value());
    }

    @Test
    void synchronousFollowerCanJoinAsynchronousLeader() throws Exception {
        ExecutorService supplierExecutor = newExecutor(1);
        ExecutorService callerExecutor = newExecutor(1);
        DefaultSingleFlight<String> singleFlight = new DefaultSingleFlight<>(supplierExecutor);
        CountDownLatch supplierStarted = new CountDownLatch(1);
        CountDownLatch releaseSupplier = new CountDownLatch(1);

        CompletableFuture<SingleFlightResult<String>> leader = singleFlight.executeAsync("key", () -> {
            supplierStarted.countDown();
            await(releaseSupplier);
            return "shared-value";
        });
        assertTrue(supplierStarted.await(1, SECONDS));

        Future<SingleFlightResult<String>> follower = callerExecutor.submit(
                () -> singleFlight.execute("key", () -> "unexpected"));
        try {
            assertThrows(TimeoutException.class, () -> follower.get(100, MILLISECONDS));
        } finally {
            releaseSupplier.countDown();
        }

        assertEquals("shared-value", leader.join().value());
        assertEquals("shared-value", follower.get().value());
    }

    @Test
    void concurrentFollowersShareSupplierFailure() throws Exception {
        ExecutorService supplierExecutor = newExecutor(1);
        DefaultSingleFlight<String> singleFlight = new DefaultSingleFlight<>(supplierExecutor);
        CountDownLatch supplierStarted = new CountDownLatch(1);
        CountDownLatch releaseSupplier = new CountDownLatch(1);
        IllegalArgumentException failure = new IllegalArgumentException("boom");

        CompletableFuture<SingleFlightResult<String>> leader = singleFlight.executeAsync("key", () -> {
            supplierStarted.countDown();
            await(releaseSupplier);
            throw failure;
        });
        assertTrue(supplierStarted.await(1, SECONDS));
        CompletableFuture<SingleFlightResult<String>> follower = singleFlight.executeAsync("key", () -> "unexpected");

        releaseSupplier.countDown();

        assertSame(failure, leader.join().exception());
        assertSame(failure, follower.join().exception());
    }

    @Test
    void forgetAllowsNewCallWhileOldCallIsStillRunning() throws Exception {
        ExecutorService supplierExecutor = newExecutor(1);
        DefaultSingleFlight<String> singleFlight = new DefaultSingleFlight<>(supplierExecutor);
        CountDownLatch firstSupplierStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstSupplier = new CountDownLatch(1);

        CompletableFuture<SingleFlightResult<String>> first = singleFlight.executeAsync("key", () -> {
            firstSupplierStarted.countDown();
            await(releaseFirstSupplier);
            return "old-value";
        });
        assertTrue(firstSupplierStarted.await(1, SECONDS));

        singleFlight.forget("key");
        SingleFlightResult<String> second = singleFlight.execute("key", () -> "new-value");
        releaseFirstSupplier.countDown();

        assertEquals("new-value", second.value());
        assertEquals("old-value", first.join().value());
    }

    @Test
    void interruptedSynchronousFollowerReturnsFailureAndRestoresInterruptFlag() throws Exception {
        ExecutorService supplierExecutor = newExecutor(1);
        ExecutorService callerExecutor = newExecutor(1);
        DefaultSingleFlight<String> singleFlight = new DefaultSingleFlight<>(supplierExecutor);
        CountDownLatch supplierStarted = new CountDownLatch(1);
        CountDownLatch releaseSupplier = new CountDownLatch(1);
        AtomicReference<Thread> followerThread = new AtomicReference<>();
        AtomicBoolean interruptedAfterExecute = new AtomicBoolean();

        CompletableFuture<SingleFlightResult<String>> leader = singleFlight.executeAsync("key", () -> {
            supplierStarted.countDown();
            await(releaseSupplier);
            return "value";
        });
        assertTrue(supplierStarted.await(1, SECONDS));

        Future<SingleFlightResult<String>> follower = callerExecutor.submit(() -> {
            followerThread.set(Thread.currentThread());
            SingleFlightResult<String> result = singleFlight.execute("key", () -> "unexpected");
            interruptedAfterExecute.set(Thread.currentThread().isInterrupted());
            return result;
        });

        try {
            waitUntilBlocked(followerThread);
            followerThread.get().interrupt();
            SingleFlightResult<String> result = follower.get(1, SECONDS);

            assertNull(result.value());
            assertInstanceOf(InterruptedException.class, result.exception());
            assertTrue(interruptedAfterExecute.get());
        } finally {
            releaseSupplier.countDown();
        }

        assertEquals("value", leader.join().value());
    }

    @Test
    void rejectsNullArguments() {
        DefaultSingleFlight<String> singleFlight = new DefaultSingleFlight<>(Runnable::run);

        assertThrows(NullPointerException.class, () -> singleFlight.execute(null, () -> "value"));
        assertThrows(NullPointerException.class, () -> singleFlight.execute("key", null));
        assertThrows(NullPointerException.class, () -> singleFlight.executeAsync(null, () -> "value"));
        assertThrows(NullPointerException.class, () -> singleFlight.executeAsync("key", null));
        assertThrows(NullPointerException.class, () -> singleFlight.forget(null));
    }

    private ExecutorService newExecutor(int threadCount) {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        executors.add(executor);
        return executor;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, SECONDS)) {
                throw new AssertionError("Timed out waiting for test coordination latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for test coordination latch", exception);
        }
    }

    private static void waitUntilBlocked(AtomicReference<Thread> threadReference) throws InterruptedException {
        long deadline = System.nanoTime() + SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            Thread thread = threadReference.get();
            if (thread != null && thread.getState() == Thread.State.WAITING) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(1);
        }
        fail("Follower did not block while awaiting the shared result");
    }
}
