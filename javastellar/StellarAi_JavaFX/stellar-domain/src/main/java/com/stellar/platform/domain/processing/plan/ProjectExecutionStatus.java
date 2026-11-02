package com.stellar.platform.domain.processing.plan;

/** Overall status for a multi-job plan execution. */
public enum ProjectExecutionStatus {
    /** Plan was not READY; no jobs started. */
    BLOCKED,
    /** Every job completed successfully (warnings allowed). */
    SUCCESS,
    /** At least one job failed or cancelled while others succeeded. */
    PARTIAL_FAILURE,
    /** Every started job failed or was cancelled. */
    FAILED,
    /** Execution cancelled before/during run with no successful jobs. */
    CANCELLED
}