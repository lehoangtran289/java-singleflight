package io.singleflight.model;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public final class InFlightCall<T> {

    private final CompletableFuture<SingleFlightResult<T>> resultFuture = new CompletableFuture<>();
    private final AtomicInteger duplicateCount = new AtomicInteger();

    public void markShared() {
        duplicateCount.incrementAndGet();
    }

    public boolean isShared() {
        return duplicateCount.get() > 0;
    }

    public SingleFlightResult<T> awaitResult() {
        try {
            return resultFuture.get();
        } catch (Exception exception) {
            Thread.currentThread().interrupt();
            return new SingleFlightResult<>(null, exception);
        }
    }

    public SingleFlightResult<T> executeSync(Supplier<? extends T> supplier, Runnable cleanup) {
        return execute(supplier, cleanup);
    }

    public void executeAsync(Supplier<? extends T> supplier, Runnable cleanup) {
        execute(supplier, cleanup);
    }

    public CompletableFuture<SingleFlightResult<T>> resultFuture() {
        return resultFuture.copy(); // independent CompletableFuture for each caller
    }

    public void signalWaiters(SingleFlightResult<T> result) {
        resultFuture.complete(result);
    }

    private SingleFlightResult<T> execute(Supplier<? extends T> supplier, Runnable cleanup) {
        var result = invoke(supplier);
        try {
            cleanup.run();
        } finally {
            signalWaiters(result);
        }
        return result;
    }

    private SingleFlightResult<T> invoke(Supplier<? extends T> supplier) {
        try {
            return new SingleFlightResult<>(supplier.get(), null);
        } catch (Exception exception) {
            return new SingleFlightResult<>(null, exception);
        }
    }
}
