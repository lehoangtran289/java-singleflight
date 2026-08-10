package io.singleflight.core;

import io.singleflight.model.InFlightCall;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DefaultSingleFlight<T> implements SingleFlight<T> {

    private static final Logger LOGGER = Logger.getLogger(DefaultSingleFlight.class.getName());

    private final ConcurrentHashMap<String, InFlightCall<T>> inFlightCalls;
    private final Executor executor;

    public DefaultSingleFlight(Executor executor) {
        this.executor = executor;
        this.inFlightCalls = new ConcurrentHashMap<>();
    }

    @Override
    public T execute(String key, Supplier<? extends T> supplier) throws InterruptedException, ExecutionException {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(supplier, "supplier must not be null");

        InFlightCall<T> newCall = new InFlightCall<>();
        InFlightCall<T> currentCall = inFlightCalls.putIfAbsent(key, newCall);

        // if there is already an in-flight call for the given key
        if (currentCall != null) {
            return currentCall.await();
        }

        // register new call and signal waiters when done
        return newCall.executeSync(supplier, () -> inFlightCalls.remove(key, newCall));
    }

    @Override
    public CompletableFuture<T> executeAsync(String key, Supplier<? extends T> supplier) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(supplier, "supplier must not be null");

        InFlightCall<T> newCall = new InFlightCall<>();
        InFlightCall<T> currentCall = inFlightCalls.putIfAbsent(key, newCall);

        // if there is already an in-flight call for the given key
        if (currentCall != null) {
            return currentCall.resultFuture();
        }

        // exec new call asynchronously and return a future
        try {
            executor.execute(() -> newCall.executeAsync(supplier, () -> inFlightCalls.remove(key, newCall)));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, e, () -> "SingleFlight executor rejected call for key: " + key);
            inFlightCalls.remove(key, newCall);
            newCall.completeExceptionally(e);
        }
        return newCall.resultFuture();
    }

    @Override
    public void forget(String key) {
        Objects.requireNonNull(key, "key must not be null");
        inFlightCalls.remove(key);
    }
}
