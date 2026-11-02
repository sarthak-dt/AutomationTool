package com.stellar.platform.processing;

/**
 * Explicit top-level completion classification. Ordinary {@link #COMPLETED} must not be used when
 * MF4 recovery ran against physical data and produced zero CAN frames.
 */
public enum ProcessingOutcome {
    COMPLETED,
    COMPLETED_WITH_WARNINGS,
    NO_CAN_DATA,
    RECOVERY_FAILED,
    UNSUPPORTED_STRUCTURE,
    FAILED,
    CANCELLED
}
