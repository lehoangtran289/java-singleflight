package io.singleflight.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SingleFlightResultTest {

    @Test
    void valueOptionalContainsNonNullValue() {
        SingleFlightResult<String> result = new SingleFlightResult<>("value", null);

        assertTrue(result.getValueOptional().isPresent());
        assertEquals("value", result.getValueOptional().orElseThrow());
        assertFalse(result.getExceptionOptional().isPresent());
    }

    @Test
    void errorOptionalContainsNonNullError() {
        RuntimeException error = new RuntimeException("boom");
        SingleFlightResult<String> result = new SingleFlightResult<>(null, error);

        assertFalse(result.getValueOptional().isPresent());
        assertTrue(result.getExceptionOptional().isPresent());
        assertSame(error, result.getExceptionOptional().orElseThrow());
    }

    @Test
    void getOrThrowReturnsValueWhenSuccessful() throws Exception {
        SingleFlightResult<String> result = new SingleFlightResult<>("value", null);

        assertEquals("value", result.getOrThrow());
    }

    @Test
    void getOrThrowThrowsStoredException() {
        IllegalArgumentException error = new IllegalArgumentException("boom");
        SingleFlightResult<String> result = new SingleFlightResult<>(null, error);

        assertSame(error, org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, result::getOrThrow));
    }
}
