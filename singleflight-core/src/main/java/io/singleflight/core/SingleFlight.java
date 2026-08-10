package io.singleflight.core;

import io.singleflight.model.SingleFlightResult;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public interface SingleFlight<T> {

    SingleFlightResult<T> execute(String key, Supplier<? extends T> supplier);

    CompletableFuture<SingleFlightResult<T>> executeAsync(String key, Supplier<? extends T> supplier);

    /**
     * Forget tells the singleflight to forget about a key.
     * Future calls to execute for this key will call the function
     * rather than waiting for an earlier call to complete.
     */
    void forget(String key);
}
