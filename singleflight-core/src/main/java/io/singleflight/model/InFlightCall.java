package io.singleflight.model;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public final class InFlightCall<T> {

    private final CompletableFuture<T> resultFuture = new CompletableFuture<>();

    public T await() throws InterruptedException, ExecutionException {
        return resultFuture.get();
    }

    public T executeSync(Supplier<? extends T> supplier, Runnable cleanup) {
        return execute(supplier, cleanup);
    }

    public void executeAsync(Supplier<? extends T> supplier, Runnable cleanup) {
        execute(supplier, cleanup);
    }

    public CompletableFuture<T> resultFuture() {
        return resultFuture.copy(); // independent CompletableFuture for each caller
    }

    public void complete(T value) {
        resultFuture.complete(value);
    }

    public void completeExceptionally(Throwable throwable) {
        resultFuture.completeExceptionally(throwable);
    }

    private T execute(Supplier<? extends T> supplier, Runnable cleanup) {
        try {
            T value = supplier.get();
            cleanup.run();
            complete(value);
            return value;
        } catch (RuntimeException | Error throwable) {
            cleanup.run();
            completeExceptionally(throwable);
            throw throwable;
        }
    }
}
