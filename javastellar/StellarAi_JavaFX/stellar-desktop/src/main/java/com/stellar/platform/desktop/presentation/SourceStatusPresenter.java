package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.processing.plan.JobExecutionStatus;

/**
 * Presentation-only mapping from already-resident source and job states to the badge
 * text and CSS class shown in Sources. Does not decide whether a source was processed.
 */
public final class SourceStatusPresenter {

    private SourceStatusPresenter() {
    }

    public record View(String badgeText, String styleClass, String footerLabel) {
    }

    public static View present(SourceStatus status, JobExecutionStatus job) {
        SourceStatus resolved = status == null ? SourceStatus.NEEDS_ATTENTION : status;
        if (resolved == SourceStatus.UNAVAILABLE) {
            return view("UNAVAILABLE", "status-failed", "Unavailable");
        }
        if (job == JobExecutionStatus.RUNNING) {
            return view(ProcessingStatusLabels.PROCESSING, "status-processing", ProcessingStatusLabels.PROCESSING);
        }
        if (job == JobExecutionStatus.CANCELLED) {
            return view(ProcessingStatusLabels.CANCELLED, "status-failed", ProcessingStatusLabels.CANCELLED);
        }
        if (job == JobExecutionStatus.FAILED || job == JobExecutionStatus.BLOCKED
                || job == JobExecutionStatus.INTERRUPTED) {
            return view(ProcessingStatusLabels.ERROR, "status-failed", ProcessingStatusLabels.ERROR);
        }
        if (job == JobExecutionStatus.SUCCESS_WITH_WARNINGS || (job != null && job.isSuccessful())
                || resolved == SourceStatus.PROCESSED) {
            return view(ProcessingStatusLabels.COMPLETED, "status-processed", ProcessingStatusLabels.COMPLETED);
        }
        return switch (resolved) {
            case READING -> view("READING", "status-processing", "Reading");
            case NEEDS_ATTENTION -> view("NEEDS ATTENTION", "status-failed", "Needs Attention");
            case UNAVAILABLE -> view("UNAVAILABLE", "status-failed", "Unavailable");
            case PROCESSED -> view("PROCESSED", "status-processed", "Processed");
            case READY -> view("READY", "status-ready", "Ready");
        };
    }

    /**
     * Prefer a stronger already-known job outcome over a weaker later write
     * (for example QUEUED must not hide a stored SUCCESS).
     */
    public static JobExecutionStatus prefer(JobExecutionStatus current, JobExecutionStatus incoming) {
        if (incoming == null) {
            return current;
        }
        if (current == null) {
            return incoming;
        }
        int incomingRank = rank(incoming);
        int currentRank = rank(current);
        if (incomingRank > currentRank) {
            return incoming;
        }
        if (incomingRank == currentRank && incoming == JobExecutionStatus.SUCCESS_WITH_WARNINGS) {
            return incoming;
        }
        return current;
    }

    private static int rank(JobExecutionStatus status) {
        if (status == null) {
            return 0;
        }
        return switch (status) {
            case SUCCESS, SUCCESS_WITH_WARNINGS -> 5;
            case RUNNING -> 4;
            case FAILED, BLOCKED -> 3;
            case CANCELLED, INTERRUPTED -> 2;
            case QUEUED, RETRY_WAIT -> 1;
        };
    }

    private static View view(String badge, String styleClass, String footer) {
        return new View(badge, styleClass, footer);
    }
}
