package io.singleflight.core;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

public interface SingleFlight<T> {

    T execute(String key, Supplier<? extends T> supplier) throws InterruptedException, ExecutionException;

    CompletableFuture<T> executeAsync(String key, Supplier<? extends T> supplier);

    /**
     * Forget tells the singleflight to forget about a key.
     * Future calls to execute for this key will call the function
     * rather than waiting for an earlier call to complete.
     */
    void forget(String key);
}
