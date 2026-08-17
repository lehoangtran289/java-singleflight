package io.singleflight.core;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.logging.Logger;

public class DefaultSingleFlight<K, V> implements SingleFlight<K, V> {

    private static final Logger LOGGER = Logger.getLogger(DefaultSingleFlight.class.getName());

    private final ConcurrentHashMap<K, CompletableFuture<V>> inFlightCallCache;
    private final Executor executor;

    public DefaultSingleFlight(Executor executor) {
        this.executor = executor;
        this.inFlightCallCache = new ConcurrentHashMap<>();
    }

    public DefaultSingleFlight() {
        this.executor = Runnable::run;
        this.inFlightCallCache = new ConcurrentHashMap<>();
    }

    @Override
    public V execute(K key, Function<K, ? extends V> computation) throws InterruptedException, ExecutionException {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(computation, "computation must not be null");

        CompletableFuture<V> future = new CompletableFuture<>();
        CompletableFuture<V> existing = inFlightCallCache.putIfAbsent(key, future);
        if (existing != null) {
            return existing.get();   // other thread is computing the result
        }

        // Do single flight computation
        try {
            future.complete(computation.apply(key));
        } catch (Exception e) {
            LOGGER.severe("Exception during computation for key " + key.getClass().getName() + " " + key + ": " + e.getMessage());
            future.completeExceptionally(e);
        } finally {
            // Remove the future from the cache once computation is complete
            inFlightCallCache.remove(key);
        }
        return future.get();
    }

    @Override
    public CompletableFuture<V> executeAsync(K key, Function<K, ? extends V> computation) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(computation, "supplier must not be null");

        CompletableFuture<V> future = new CompletableFuture<>();
        CompletableFuture<V> existing = inFlightCallCache.putIfAbsent(key, future);
        if (existing != null) {
            return existing.copy();   // other thread is computing the result
        }

        // We own this key — nothing below runs inside a map callback.
        try {
            executor.execute(() -> {
                try {
                    future.complete(computation.apply(key));
                } catch (Exception e) {
                    LOGGER.severe("Exception during computation for key " + key.getClass().getName() + " " + key + ": " + e.getMessage());
                    future.completeExceptionally(e);
                } finally {
                    inFlightCallCache.remove(key, future);
                }
            });
        } catch (Exception e) {
            LOGGER.severe("Exception with async executor for key " + key.getClass().getName() + " " + key + ": " + e.getMessage());
            inFlightCallCache.remove(key, future);
            future.completeExceptionally(e);
        }

        return future.copy();
    }

    @Override
    public void forget(K key) {
        Objects.requireNonNull(key, "key must not be null");
        inFlightCallCache.remove(key);
    }
}
