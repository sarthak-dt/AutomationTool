package com.stellar.platform.processing;

/**
 * High-level stages reported during a measurement decode run.
 */
public enum ProcessingStage {
    IDLE,
    VALIDATING,
    READING,
    MF4_METADATA_DISCOVERY,
    MF4_SCHEMA_BUILDING,
    MF4_DECODING,
    MF4_RECOVERY,
    DBC_DECODING,
    FINALIZING,
    COMPLETED,
    FAILED,
    CANCELLED
}
