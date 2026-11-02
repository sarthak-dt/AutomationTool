package com.stellar.platform.domain.processing.plan;

/** Typed diagnostics for processing-plan validation and job execution. */
public enum ProcessingPlanDiagnosticCode {
    UNCONFIRMED_DEFINITION,
    NO_EFFECTIVE_DEFINITION,
    AMBIGUOUS_DEFINITION,
    SOURCE_NOT_READY,
    CHANNEL_NOT_AVAILABLE,
    DEFINITION_NOT_AVAILABLE,
    SOURCE_REMOVED,
    EMPTY_SELECTION,
    /** Selected-channel frames observed during execution differ from inventory metadata. */
    FRAME_COUNT_MISMATCH,
    /** Non-fatal reader/DBC/pipeline diagnostic copied from {@code ProcessingResult}. */
    PIPELINE_DIAGNOSTIC
}
