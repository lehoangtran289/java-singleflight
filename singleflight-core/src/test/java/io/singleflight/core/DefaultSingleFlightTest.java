package io.singleflight.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
import java.util.function.Supplier;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    void executeReturnsSupplierValue() throws Exception {
        DefaultSingleFlight<String> singleFlight = new DefaultSingleFlight<>(Runnable::run);

        String value = singleFlight.execute("key", () -> "value");

        assertEquals("value", value);
    }

    @Test
    void executeAllowsBlankKeysAndNullValues() throws Exception {
        DefaultSingleFlight<String> singleFlight = new DefaultSingleFlight<>(Runnable::run);

        String value = singleFlight.execute("", () -> null);

        assertNull(value);
    }

    @Test
    void executeCapturesSupplierFailure() {
        DefaultSingleFlight<String> singleFlight = new DefaultSingleFlight<>(Runnable::run);
        IllegalStateException failure = new IllegalStateException("boom");

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> singleFlight.execute("key", () -> {
            throw failure;
        }));

        assertSame(failure, thrown);
    }

    @Test
    void executeRunsAgainAfterPreviousCallCompletes() throws Exception {
        DefaultSingleFlight<Integer> singleFlight = new DefaultSingleFlight<>(Runnable::run);
        AtomicInteger invocationCount = new AtomicInteger();

        Integer first = singleFlight.execute("key", invocationCount::incrementAndGet);
        Integer second = singleFlight.execute("key", invocationCount::incrementAndGet);

        assertEquals(1, first);
        assertEquals(2, second);
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

        Future<String> leader = callers.submit(() -> singleFlight.execute("key", () -> {
            invocationCount.incrementAndGet();
            supplierStarted.countDown();
            await(releaseSupplier);
            return "shared-value";
        }));

        assertTrue(supplierStarted.await(1, SECONDS));
        Future<String> follower = callers.submit(() -> {
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

        assertEquals("shared-value", leader.get());
        assertEquals("shared-value", follower.get());
        assertEquals(1, invocationCount.get());
    }

    @Test
    void executeDoesNotCoalesceDifferentKeys() {
        ExecutorService supplierExecutor = newExecutor(2);
        DefaultSingleFlight<String> singleFlight = new DefaultSingleFlight<>(supplierExecutor);
        CountDownLatch bothSuppliersStarted = new CountDownLatch(2);

        CompletableFuture<String> first = singleFlight.executeAsync("first", () -> {
            bothSuppliersStarted.countDown();
            await(bothSuppliersStarted);
            return "first-value";
        });
        CompletableFuture<String> second = singleFlight.executeAsync("second", () -> {
            bothSuppliersStarted.countDown();
            await(bothSuppliersStarted);
            return "second-value";
        });

        assertEquals("first-value", first.join());
        assertEquals("second-value", second.join());
    }

    @Test
    void executeAsyncReturnsSupplierValue() {
        DefaultSingleFlight<String> singleFlight = new DefaultSingleFlight<>(Runnable::run);

        String value = singleFlight.executeAsync("key", () -> "value").join();

        assertEquals("value", value);
    }

    @Test
    void executeAsyncCapturesSupplierException() {
        DefaultSingleFlight<String> singleFlight = new DefaultSingleFlight<>(Runnable::run);
        IllegalStateException failure = new IllegalStateException("boom");

        CompletableFuture<String> future = singleFlight.executeAsync("key", () -> {
            throw failure;
        });

        CompletionException thrown = assertThrows(CompletionException.class, future::join);
        assertSame(failure, thrown.getCause());
    }

    @Test
    void executeAsyncReportsExecutorRejectionAsResultFailure() {
        RejectedExecutionException rejection = new RejectedExecutionException("rejected");
        DefaultSingleFlight<String> singleFlight = new DefaultSingleFlight<>(command -> {
            throw rejection;
        });

        CompletableFuture<String> future = singleFlight.executeAsync("key", () -> "unused");

        CompletionException thrown = assertThrows(CompletionException.class, future::join);
        assertSame(rejection, thrown.getCause());
    }

    @Test
    void executeAsyncCoalescesConcurrentCallsAndReturnsIndependentFutures() throws Exception {
        ExecutorService supplierExecutor = newExecutor(1);
        DefaultSingleFlight<String> singleFlight = new DefaultSingleFlight<>(supplierExecutor);
        CountDownLatch supplierStarted = new CountDownLatch(1);
        CountDownLatch releaseSupplier = new CountDownLatch(1);
        AtomicInteger invocationCount = new AtomicInteger();

        CompletableFuture<String> leader = singleFlight.executeAsync("key", () -> {
            invocationCount.incrementAndGet();
            supplierStarted.countDown();
            await(releaseSupplier);
            return "shared-value";
        });

        assertTrue(supplierStarted.await(1, SECONDS));
        CompletableFuture<String> follower = singleFlight.executeAsync("key", () -> {
            invocationCount.incrementAndGet();
            return "unexpected";
        });

        assertNotSame(leader, follower);
        releaseSupplier.countDown();

        assertEquals("shared-value", leader.join());
        assertEquals("shared-value", follower.join());
        assertEquals(1, invocationCount.get());
    }

    @Test
    void fourThreadsCallingDuringOneSlowTaskShareSingleExecution() throws Exception {
        ExecutorService supplierExecutor = newExecutor(1);
        ExecutorService callers = newExecutor(4);
        DefaultSingleFlight<Integer> singleFlight = new DefaultSingleFlight<>(supplierExecutor);
        CountDownLatch supplierStarted = new CountDownLatch(1);
        CountDownLatch releaseSupplier = new CountDownLatch(1);
        AtomicInteger invocationCount = new AtomicInteger();

        List<Future<Integer>> results = submitConcurrentAsyncCalls(callers, 4,
                () -> singleFlight.executeAsync("key", () -> {
                    int invocation = invocationCount.incrementAndGet();
                    supplierStarted.countDown();
                    await(releaseSupplier);
                    return invocation;
                }));

        try {
            assertTrue(supplierStarted.await(1, SECONDS));
            assertEquals(1, invocationCount.get());
        } finally {
            releaseSupplier.countDown();
        }

        for (Future<Integer> result : results) {
            assertEquals(1, result.get(1, SECONDS));
        }
        assertEquals(1, invocationCount.get());
    }

    @Test
    void callerBurstsSeparatedByTaskCompletionRunOncePerBurst() throws Exception {
        ExecutorService supplierExecutor = newExecutor(1);
        ExecutorService callers = newExecutor(2);
        DefaultSingleFlight<Integer> singleFlight = new DefaultSingleFlight<>(supplierExecutor);
        CountDownLatch firstSupplierStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstSupplier = new CountDownLatch(1);
        CountDownLatch secondSupplierStarted = new CountDownLatch(1);
        CountDownLatch releaseSecondSupplier = new CountDownLatch(1);
        AtomicInteger invocationCount = new AtomicInteger();

        Supplier<CompletableFuture<Integer>> call = () -> singleFlight.executeAsync("key", () -> {
            int invocation = invocationCount.incrementAndGet();
            if (invocation == 1) {
                firstSupplierStarted.countDown();
                await(releaseFirstSupplier);
            } else if (invocation == 2) {
                secondSupplierStarted.countDown();
                await(releaseSecondSupplier);
            }
            return invocation;
        });

        List<Future<Integer>> firstBurst = submitConcurrentAsyncCalls(callers, 2, call);
        try {
            assertTrue(firstSupplierStarted.await(1, SECONDS));
            assertEquals(1, invocationCount.get());
        } finally {
            releaseFirstSupplier.countDown();
        }
        for (Future<Integer> result : firstBurst) {
            assertEquals(1, result.get(1, SECONDS));
        }

        // Arriving three seconds after a one-second task means the first task has completed.
        List<Future<Integer>> secondBurst = submitConcurrentAsyncCalls(callers, 2, call);
        try {
            assertTrue(secondSupplierStarted.await(1, SECONDS));
            assertEquals(2, invocationCount.get());
        } finally {
            releaseSecondSupplier.countDown();
        }
        for (Future<Integer> result : secondBurst) {
            assertEquals(2, result.get(1, SECONDS));
        }
        assertEquals(2, invocationCount.get());
    }

    @Test
    void cancellingOneAsyncFutureDoesNotCancelOtherCallers() throws Exception {
        ExecutorService supplierExecutor = newExecutor(1);
        DefaultSingleFlight<String> singleFlight = new DefaultSingleFlight<>(supplierExecutor);
        CountDownLatch supplierStarted = new CountDownLatch(1);
        CountDownLatch releaseSupplier = new CountDownLatch(1);

        CompletableFuture<String> first = singleFlight.executeAsync("key", () -> {
            supplierStarted.countDown();
            await(releaseSupplier);
            return "value";
        });

        assertTrue(supplierStarted.await(1, SECONDS));
        CompletableFuture<String> second = singleFlight.executeAsync("key", () -> "unexpected");

        assertTrue(first.cancel(true));
        releaseSupplier.countDown();

        assertTrue(first.isCancelled());
        assertEquals("value", second.join());
    }

    @Test
    void synchronousFollowerCanJoinAsynchronousLeader() throws Exception {
        ExecutorService supplierExecutor = newExecutor(1);
        ExecutorService callerExecutor = newExecutor(1);
        DefaultSingleFlight<String> singleFlight = new DefaultSingleFlight<>(supplierExecutor);
        CountDownLatch supplierStarted = new CountDownLatch(1);
        CountDownLatch releaseSupplier = new CountDownLatch(1);

        CompletableFuture<String> leader = singleFlight.executeAsync("key", () -> {
            supplierStarted.countDown();
            await(releaseSupplier);
            return "shared-value";
        });
        assertTrue(supplierStarted.await(1, SECONDS));

        Future<String> follower = callerExecutor.submit(
                () -> singleFlight.execute("key", () -> "unexpected"));
        try {
            assertThrows(TimeoutException.class, () -> follower.get(100, MILLISECONDS));
        } finally {
            releaseSupplier.countDown();
        }

        assertEquals("shared-value", leader.join());
        assertEquals("shared-value", follower.get());
    }

    @Test
    void concurrentFollowersShareSupplierFailure() throws Exception {
        ExecutorService supplierExecutor = newExecutor(1);
        DefaultSingleFlight<String> singleFlight = new DefaultSingleFlight<>(supplierExecutor);
        CountDownLatch supplierStarted = new CountDownLatch(1);
        CountDownLatch releaseSupplier = new CountDownLatch(1);
        IllegalArgumentException failure = new IllegalArgumentException("boom");

        CompletableFuture<String> leader = singleFlight.executeAsync("key", () -> {
            supplierStarted.countDown();
            await(releaseSupplier);
            throw failure;
        });
        assertTrue(supplierStarted.await(1, SECONDS));
        CompletableFuture<String> follower = singleFlight.executeAsync("key", () -> "unexpected");

        releaseSupplier.countDown();

        CompletionException leaderThrown = assertThrows(CompletionException.class, leader::join);
        CompletionException followerThrown = assertThrows(CompletionException.class, follower::join);
        assertSame(failure, leaderThrown.getCause());
        assertSame(failure, followerThrown.getCause());
    }

    @Test
    void forgetAllowsNewCallWhileOldCallIsStillRunning() throws Exception {
        ExecutorService supplierExecutor = newExecutor(1);
        DefaultSingleFlight<String> singleFlight = new DefaultSingleFlight<>(supplierExecutor);
        CountDownLatch firstSupplierStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstSupplier = new CountDownLatch(1);

        CompletableFuture<String> first = singleFlight.executeAsync("key", () -> {
            firstSupplierStarted.countDown();
            await(releaseFirstSupplier);
            return "old-value";
        });
        assertTrue(firstSupplierStarted.await(1, SECONDS));

        singleFlight.forget("key");
        String second = singleFlight.execute("key", () -> "new-value");
        releaseFirstSupplier.countDown();

        assertEquals("new-value", second);
        assertEquals("old-value", first.join());
    }

    @Test
    void interruptedSynchronousFollowerThrowsInterruptedException() throws Exception {
        ExecutorService supplierExecutor = newExecutor(1);
        ExecutorService callerExecutor = newExecutor(1);
        DefaultSingleFlight<String> singleFlight = new DefaultSingleFlight<>(supplierExecutor);
        CountDownLatch supplierStarted = new CountDownLatch(1);
        CountDownLatch releaseSupplier = new CountDownLatch(1);
        AtomicReference<Thread> followerThread = new AtomicReference<>();
        AtomicBoolean interruptedAfterExecute = new AtomicBoolean();

        CompletableFuture<String> leader = singleFlight.executeAsync("key", () -> {
            supplierStarted.countDown();
            await(releaseSupplier);
            return "value";
        });
        assertTrue(supplierStarted.await(1, SECONDS));

        Future<InterruptedException> follower = callerExecutor.submit(() -> {
            followerThread.set(Thread.currentThread());
            try {
                singleFlight.execute("key", () -> "unexpected");
                return null;
            } catch (InterruptedException expected) {
                interruptedAfterExecute.set(Thread.currentThread().isInterrupted());
                return expected;
            }
        });

        try {
            waitUntilBlocked(followerThread);
            followerThread.get().interrupt();
            InterruptedException result = follower.get(1, SECONDS);

            assertNotNull(result);
            assertFalse(interruptedAfterExecute.get());
        } finally {
            releaseSupplier.countDown();
        }

        assertEquals("value", leader.join());
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

    private static <T> List<Future<T>> submitConcurrentAsyncCalls(
            ExecutorService callers,
            int callerCount,
            Supplier<CompletableFuture<T>> call) throws InterruptedException {
        CountDownLatch callersReady = new CountDownLatch(callerCount);
        CountDownLatch startCalls = new CountDownLatch(1);
        CountDownLatch callsRegistered = new CountDownLatch(callerCount);
        List<Future<T>> results = new ArrayList<>(callerCount);

        for (int caller = 0; caller < callerCount; caller++) {
            results.add(callers.submit(() -> {
                callersReady.countDown();
                await(startCalls);
                CompletableFuture<T> result = call.get();
                callsRegistered.countDown();
                return result.join();
            }));
        }

        assertTrue(callersReady.await(1, SECONDS));
        startCalls.countDown();
        assertTrue(callsRegistered.await(1, SECONDS));
        return results;
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
