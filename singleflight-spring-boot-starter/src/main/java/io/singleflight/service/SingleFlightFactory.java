package io.singleflight.service;

import io.singleflight.core.DefaultSingleFlight;
import io.singleflight.core.SingleFlight;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Creates type-safe {@link SingleFlight} groups that share the starter's executor.
 */
public final class SingleFlightFactory {

    private final Executor executor;

    public SingleFlightFactory(Executor executor) {
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
    }

    /**
     * Creates an independent single-flight group.
     *
     * @param <T> value type returned by this group
     * @return a new single-flight group backed by the configured executor
     */
    public <T> SingleFlight<T> create() {
        return new DefaultSingleFlight<>(executor);
    }
}
