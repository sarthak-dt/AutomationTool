package com.stellar.platform.domain.processing.plan;

/**
 * Lightweight plan summary for future UI. No project timeline duration/start/end.
 */
public record ProcessingPlanSummary(
        int selectedSources,
        int selectedChannels,
        int processingJobs,
        long selectedFrames,
        int readyJobs,
        int blockedJobs
) {
}
