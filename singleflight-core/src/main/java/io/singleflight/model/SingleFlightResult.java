package io.singleflight.model;

import java.util.Optional;

public record SingleFlightResult<T>(T value, Exception exception) {

    public Optional<T> getValueOptional() {
        return Optional.ofNullable(value);
    }

    public Optional<Exception> getExceptionOptional() {
        return Optional.ofNullable(exception);
    }

    public boolean hasException() {
        return exception != null;
    }

    public T getOrThrow() throws Exception {
        if (exception != null) {
            throw exception;
        }
        return value;
    }
}
