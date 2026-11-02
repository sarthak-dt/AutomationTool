package com.stellar.platform.domain.processing;

/**
 * Stable classification for a processing failure.
 *
 * <p>Persisted in {@code processing_jobs.failure_class} and {@code processing_errors.classification},
 * so the value must stay stable across releases. Drives automatic retry: only failures that are
 * genuinely transient are retryable. Corrupt or missing input is never retried — repeating a decode
 * of a damaged file just burns time and produces the same result.</p>
 */
public enum ProcessingFailureClass {
    /** User asked to stop. Never retried. */
    CANCELLED(false, "Cancelled"),
    /** Source or definition file no longer exists at the recorded path. */
    INPUT_MISSING(false, "Input file missing"),
    /** Source content changed since the artifact or checkpoint was produced. */
    INPUT_CHANGED(false, "Input file changed"),
    /** DBC/definition could not be parsed or does not apply to the selected channel. */
    INVALID_DEFINITION(false, "Invalid definition"),
    /** Measurement structure is damaged beyond what recovery can repair. */
    CORRUPT_INPUT(false, "Corrupt measurement data"),
    /** Analysis Storage does not have enough free space to admit the job. */
    INSUFFICIENT_STORAGE(false, "Insufficient analysis storage space"),
    /** Worker hit a memory or concurrency ceiling; a later attempt may succeed. */
    RESOURCE_EXHAUSTED(true, "Resources exhausted"),
    /** Transient filesystem error such as a locked or temporarily unavailable file. */
    TRANSIENT_IO(true, "Temporary I/O error"),
    /** Worker JVM died while the job was running. */
    WORKER_CRASH(true, "Worker stopped unexpectedly"),
    /** Worker stopped answering heartbeats and was terminated. */
    WORKER_UNRESPONSIVE(true, "Worker became unresponsive"),
    /** Unexpected defect. Not retried automatically because the state is unknown. */
    INTERNAL_ERROR(false, "Internal error");

    private final boolean retryable;
    private final String displayText;

    ProcessingFailureClass(boolean retryable, String displayText) {
        this.retryable = retryable;
        this.displayText = displayText;
    }

    /** True when an automatic bounded retry is safe. */
    public boolean isRetryable() {
        return retryable;
    }

    /** Short user-facing reason. Never an internal stack trace. */
    public String displayText() {
        return displayText;
    }

    /** Tolerant parse for persisted values written by an older or newer build. */
    public static ProcessingFailureClass parse(String value, ProcessingFailureClass fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return fallback;
        }
    }
}
