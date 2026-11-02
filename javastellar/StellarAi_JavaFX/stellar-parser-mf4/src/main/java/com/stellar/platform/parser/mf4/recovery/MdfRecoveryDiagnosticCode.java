package com.stellar.platform.parser.mf4.recovery;

/**
 * Typed diagnostics for the MF4 Recovery Engine.
 */
public enum MdfRecoveryDiagnosticCode {
    RECOVERY_REQUIRED,
    RECOVERY_STARTED,
    EFFECTIVE_BOUNDARY_EXTENDED,
    ZERO_DECLARED_CYCLES_WITH_DATA,
    RECOVERED_RECORD,
    INVALID_RECOVERY_CANDIDATE,
    UNKNOWN_RECORD_ID,
    TRUNCATED_FINAL_RECORD,
    RESYNC_FAILED,
    UNSUPPORTED_RECOVERY_SOURCE,
    RECOVERY_COMPLETED,
    PARTIAL_RECOVERY,
    CANCELLED,
    NO_VALID_RECORDS_IN_REGION,
    VLSD_RECORD_SKIPPED,
    NON_CAN_GROUP_SKIPPED,
    /** Declared DT payload length is zero/unusable for physical recovery. */
    UNFINALIZED_DT_LENGTH_UNUSABLE,
    /** Recovery region extended beyond the declared DT payload (structurally bounded). */
    EXTENDED_UNFINALIZED_DT_RECOVERY,
    /** Aggregated count of raw "##" candidates rejected by structural MDF validation. */
    FALSE_MDF_BLOCK_MARKER_IGNORED
}
