package com.stellar.platform.processing.runtime;

import com.stellar.platform.domain.processing.ProcessingFailureClass;

import java.time.Duration;

/**
 * Bounded automatic retry for transient failures only.
 */
public final class RetryRecoveryManager {

    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    public boolean shouldRetry(ProcessingFailureClass failureClass, int attempt, int maxAttempts) {
        if (failureClass == null || !failureClass.isRetryable()) {
            return false;
        }
        return attempt < Math.max(1, maxAttempts);
    }

    public Duration backoff(int attempt) {
        int safe = Math.max(0, attempt);
        long seconds = 1L << Math.min(safe, 4);
        return Duration.ofSeconds(seconds);
    }
}
