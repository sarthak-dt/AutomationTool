package com.stellar.platform.dbc.parse;

/**
 * Typed DBC / decode diagnostic codes for UI reporting.
 */
public enum DbcDiagnosticCode {
    MALFORMED_DBC,
    /** A BO_ size is outside the supported 0..64 byte range. The file is rejected and the size is not truncated. */
    UNSUPPORTED_MESSAGE_SIZE,
    UNSUPPORTED_CONSTRUCT,
    UNMATCHED_CAN_ID,
    INSUFFICIENT_PAYLOAD,
    INVALID_SIGNAL_DEFINITION,
    IMPOSSIBLE_START_BIT_OR_LENGTH,
    OUT_OF_RANGE_PHYSICAL_VALUE,
    UNSUPPORTED_MULTIPLEXING,
    /**
     * Frame matched a multiplexed DBC message but no leaf (non-selector) signal was applicable.
     * Typical when CAN ID matches while payload multiplex values do not activate any branch.
     */
    MUX_NO_ACTIVE_LEAF,
    MALFORMED_FRAME,
    /** SG_ line failed before a more specific component could be identified. */
    DBC_SG_MALFORMED,
    DBC_SG_INVALID_NAME,
    DBC_SG_INVALID_MULTIPLEX,
    DBC_SG_INVALID_BIT_LAYOUT,
    DBC_SG_INVALID_FACTOR_OFFSET,
    DBC_SG_INVALID_RANGE,
    DBC_SG_UNTERMINATED_UNIT,
    DBC_SG_INVALID_RECEIVER,
    /** Extracted raw matches GenSigInvalidValue or GenSigInactiveValue. The sample is still emitted. */
    INVALID_RAW_VALUE,
    /** IEEE conversion or factor/offset produced NaN or infinity. The sample is still emitted. */
    NON_FINITE_PHYSICAL_VALUE
}
