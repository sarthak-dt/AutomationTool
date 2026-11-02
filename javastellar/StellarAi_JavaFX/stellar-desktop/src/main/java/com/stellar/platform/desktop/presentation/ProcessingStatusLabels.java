package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.ProjectProcessingStatus;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;

/**
 * User-facing processing status. Informational diagnostics stay in Details and do not
 * change a successful run into a warning status. Stored {@code COMPLETED_WITH_WARNINGS}
 * from older projects is shown as {@link #COMPLETED}.
 */
public final class ProcessingStatusLabels {

    public static final String COMPLETED = "COMPLETED";
    public static final String ERROR = "ERROR";
    public static final String PROCESSING = "PROCESSING";
    public static final String CANCELLED = "CANCELLED";

    public static final String STYLE_COMPLETED = "badge-ready";
    public static final String STYLE_ERROR = "badge-invalid";
    public static final String STYLE_PROCESSING = "badge-running";
    public static final String STYLE_CANCELLED = "badge-idle";

    private ProcessingStatusLabels() {
    }

    public static String forJob(JobExecutionStatus status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case SUCCESS, SUCCESS_WITH_WARNINGS -> COMPLETED;
            case FAILED, BLOCKED, INTERRUPTED -> ERROR;
            case RUNNING, QUEUED, RETRY_WAIT -> PROCESSING;
            case CANCELLED -> CANCELLED;
        };
    }

    public static String styleForJob(JobExecutionStatus status) {
        if (status == null) {
            return STYLE_COMPLETED;
        }
        return switch (status) {
            case SUCCESS, SUCCESS_WITH_WARNINGS -> STYLE_COMPLETED;
            case FAILED, BLOCKED, INTERRUPTED -> STYLE_ERROR;
            case RUNNING, QUEUED, RETRY_WAIT -> STYLE_PROCESSING;
            case CANCELLED -> STYLE_CANCELLED;
        };
    }

    public static String forProject(ProjectProcessingStatus status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case RUNNING -> PROCESSING;
            case CANCELLED -> CANCELLED;
            case FAILED, NO_CAN_DATA, RECOVERY_FAILED, UNSUPPORTED_STRUCTURE -> ERROR;
            case COMPLETED, COMPLETED_WITH_WARNINGS -> COMPLETED;
            case IDLE, READY -> status.name();
        };
    }

    public static String styleForProject(ProjectProcessingStatus status) {
        if (status == null) {
            return STYLE_COMPLETED;
        }
        return switch (status) {
            case RUNNING -> STYLE_PROCESSING;
            case CANCELLED -> STYLE_CANCELLED;
            case FAILED, NO_CAN_DATA, RECOVERY_FAILED, UNSUPPORTED_STRUCTURE -> STYLE_ERROR;
            default -> STYLE_COMPLETED;
        };
    }

    /** Persisted run text, including older {@code COMPLETED_WITH_WARNINGS} rows. */
    public static String forStored(String status) {
        if (status == null || status.isBlank()) {
            return "";
        }
        if (ProcessingRunStatuses.isCancelled(status)) {
            return CANCELLED;
        }
        if (ProcessingRunStatuses.isRunning(status)) {
            return PROCESSING;
        }
        if (ProcessingRunStatuses.isFailed(status)) {
            return ERROR;
        }
        if (ProcessingRunStatuses.isCompleted(status)) {
            return COMPLETED;
        }
        return status;
    }
}
