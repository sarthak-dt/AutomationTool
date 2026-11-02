package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.processing.plan.ProjectExecutionStatus;

import java.util.Locale;

/**
 * Interpretation of the persisted {@code processing_run.status} text. Presentation-only —
 * the stored status strings themselves are unchanged.
 */
public final class ProcessingRunStatuses {

    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String COMPLETED_WITH_WARNINGS = "COMPLETED_WITH_WARNINGS";
    /** Some jobs produced usable artifacts and some did not; both readings below must be true. */
    public static final String COMPLETED_WITH_FAILURES = "COMPLETED_WITH_FAILURES";
    public static final String CANCELLED = "CANCELLED";
    public static final String FAILED = "FAILED";

    private ProcessingRunStatuses() {
    }

    /**
     * Persisted status for a finished batch. The engine already classifies the batch; recording
     * that verdict verbatim is what lets a reopened project resolve Result vs Resume instead of
     * every run looking successful.
     */
    public static String forBatch(ProjectExecutionStatus status) {
        if (status == null) {
            return FAILED;
        }
        return switch (status) {
            case SUCCESS -> COMPLETED;
            case PARTIAL_FAILURE -> COMPLETED_WITH_FAILURES;
            case CANCELLED -> CANCELLED;
            case FAILED, BLOCKED -> FAILED;
        };
    }

    /**
     * Aggregate persisted run status from authoritative job outcomes. Informational
     * warning jobs stay COMPLETED. A failed, blocked, or cancelled job is not collapsed
     * into success.
     */
    public static String forJobs(
            ProjectExecutionStatus batchStatus,
            java.util.Collection<com.stellar.platform.domain.processing.plan.IndependentJobResult> jobs
    ) {
        String fromBatch = forBatch(batchStatus);
        if (FAILED.equals(fromBatch)
                || COMPLETED_WITH_FAILURES.equals(fromBatch)
                || CANCELLED.equals(fromBatch)) {
            return fromBatch;
        }
        if (jobs == null || jobs.isEmpty()) {
            return fromBatch;
        }
        for (com.stellar.platform.domain.processing.plan.IndependentJobResult job : jobs) {
            if (job == null) {
                continue;
            }
            if (job.status() == com.stellar.platform.domain.processing.plan.JobExecutionStatus.FAILED
                    || job.status() == com.stellar.platform.domain.processing.plan.JobExecutionStatus.CANCELLED
                    || job.status() == com.stellar.platform.domain.processing.plan.JobExecutionStatus.BLOCKED) {
                return COMPLETED_WITH_FAILURES;
            }
        }
        return fromBatch;
    }

    public static boolean isCompleted(String status) {
        String upper = upper(status);
        return upper.contains("COMPLETE") || upper.contains("SUCCESS") || upper.contains("SUCCEEDED");
    }

    /**
     * Terminal success that still carried non-fatal diagnostics. Does not include
     * {@link #COMPLETED_WITH_FAILURES}.
     */
    public static boolean isCompletedWithWarnings(String status) {
        String upper = upper(status);
        return COMPLETED_WITH_WARNINGS.equals(upper) || upper.contains("SUCCESS_WITH_WARNINGS");
    }

    public static boolean isFailed(String status) {
        String upper = upper(status);
        return upper.contains("FAIL") || upper.contains("ERROR");
    }

    public static boolean isCancelled(String status) {
        return upper(status).contains("CANCEL");
    }

    /**
     * A persisted RUNNING row means the previous session ended mid-execution: the run can never
     * still be live in a fresh process, so it is treated as interrupted.
     */
    public static boolean isRunning(String status) {
        String upper = upper(status);
        return upper.contains("RUNNING") || upper.contains("IN_PROGRESS") || upper.contains("STARTED");
    }

    public static boolean isInterrupted(String status) {
        return !isCompleted(status) && (isRunning(status) || isCancelled(status));
    }

    /**
     * Successful or failed terminal outcomes must not be rewritten to CANCELLED
     * when a late Cancel arrives after the worker already finished.
     */
    public static boolean isProtectedTerminal(String status) {
        return isCompleted(status) || isFailed(status);
    }

    private static String upper(String status) {
        return status == null ? "" : status.toUpperCase(Locale.ROOT);
    }
}
