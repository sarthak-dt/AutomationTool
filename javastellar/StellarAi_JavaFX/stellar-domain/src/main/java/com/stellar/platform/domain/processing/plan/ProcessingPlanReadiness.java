package com.stellar.platform.domain.processing.plan;

/** Overall readiness of a {@link ProjectProcessingPlan}. */
public enum ProcessingPlanReadiness {
    /** No channels selected. */
    EMPTY,
    /** Every selected job is executable (DBC mapping or Auto Decode native-only). */
    READY,
    /** At least one job is only RECOMMENDED; none are hard-invalid. */
    REQUIRES_CONFIRMATION,
    /** Selection contains blocked/invalid jobs (NO_DBC, AMBIGUOUS, missing source/channel, etc.). */
    INVALID_SELECTION
}
