package io.singleflight.core;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

public interface SingleFlight<K, V> {

    V execute(K key, Function<K, ? extends V> computation) throws InterruptedException, ExecutionException;

    CompletableFuture<V> executeAsync(K key, Function<K, ? extends V> computation);

    /**
     * Forget tells the singleflight to forget about a key.
     * Future calls to execute for this key will call the function
     * rather than waiting for an earlier call to complete.
     */
    void forget(K key);
}
