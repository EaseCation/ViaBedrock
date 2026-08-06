package net.raphimc.viabedrock.protocol.storage;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaCustomPayloadRateLimitStorageTest {

    @Test
    void rejectsMessagesAndBytesPastTheConnectionWindowLimits() {
        final JavaCustomPayloadRateLimitStorage limiter = new JavaCustomPayloadRateLimitStorage();

        assertTrue(limiter.tryAcquire(1L, 3, 2, 5));
        assertFalse(limiter.tryAcquire(2L, 3, 2, 5));
        assertTrue(limiter.tryAcquire(TimeUnit.MINUTES.toNanos(1L) + 1L, 5, 2, 5));
    }

    @Test
    void rejectsMoreMessagesThanTheConfiguredCount() {
        final JavaCustomPayloadRateLimitStorage limiter = new JavaCustomPayloadRateLimitStorage();

        assertTrue(limiter.tryAcquire(1L, 1, 1, 10));
        assertFalse(limiter.tryAcquire(2L, 1, 1, 10));
    }
}
