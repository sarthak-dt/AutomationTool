package com.stellar.platform.domain.processing.plan;

/**
 * Explicit lifecycle status for one ProcessingJob execution.
 *
 * <p>Also the durable status of a row in the {@code processing_jobs} ledger, so an in-flight
 * job survives application termination. {@link #RETRY_WAIT} and {@link #INTERRUPTED} only ever
 * originate from the durable ledger — a single in-memory plan execution never produces them.</p>
 */
public enum JobExecutionStatus {
    QUEUED,
    RUNNING,
    /** Transient failure classified as retryable; waiting out a backoff delay before re-running. */
    RETRY_WAIT,
    SUCCESS,
    SUCCESS_WITH_WARNINGS,
    FAILED,
    CANCELLED,
    BLOCKED,
    /**
     * Was {@link #RUNNING} when the worker or application terminated. Distinct from
     * {@link #CANCELLED}: the user did not ask to stop, so a compatible checkpoint may resume it.
     */
    INTERRUPTED;

    /** Terminal states never re-entered without an explicit new attempt. */
    public boolean isTerminal() {
        return switch (this) {
            case SUCCESS, SUCCESS_WITH_WARNINGS, FAILED, CANCELLED, BLOCKED -> true;
            case QUEUED, RUNNING, RETRY_WAIT, INTERRUPTED -> false;
        };
    }

    /** True when the job produced usable artifacts. */
    public boolean isSuccessful() {
        return this == SUCCESS || this == SUCCESS_WITH_WARNINGS;
    }

    /** True when restart recovery may offer a resume for this job. */
    public boolean isResumable() {
        return this == INTERRUPTED || this == RETRY_WAIT;
    }
}
