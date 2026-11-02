package com.stellar.platform.processing.runtime;

import com.stellar.platform.domain.processing.ProcessingFailureClass;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryRecoveryManagerTest {

    private final RetryRecoveryManager manager = new RetryRecoveryManager();

    @Test
    void transientFailuresRetryUntilCap() {
        assertTrue(manager.shouldRetry(ProcessingFailureClass.TRANSIENT_IO, 1, 3));
        assertTrue(manager.shouldRetry(ProcessingFailureClass.WORKER_CRASH, 2, 3));
        assertFalse(manager.shouldRetry(ProcessingFailureClass.TRANSIENT_IO, 3, 3));
        assertEquals(1L, manager.backoff(0).toSeconds());
        assertEquals(2L, manager.backoff(1).toSeconds());
        assertEquals(4L, manager.backoff(2).toSeconds());
    }

    @Test
    void corruptInputIsNeverRetried() {
        assertFalse(manager.shouldRetry(ProcessingFailureClass.CORRUPT_INPUT, 0, 3));
        assertFalse(manager.shouldRetry(ProcessingFailureClass.INPUT_MISSING, 0, 3));
        assertFalse(manager.shouldRetry(ProcessingFailureClass.CANCELLED, 0, 3));
        assertFalse(manager.shouldRetry(ProcessingFailureClass.INPUT_CHANGED, 0, 3));
    }
}
