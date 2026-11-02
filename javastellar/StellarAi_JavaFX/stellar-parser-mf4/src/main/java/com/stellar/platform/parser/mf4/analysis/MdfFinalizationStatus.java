package com.stellar.platform.parser.mf4.analysis;

/**
 * Explicit MF4 finalization classification derived from combined evidence.
 */
public enum MdfFinalizationStatus {
    FINALIZED,
    UNFINALIZED,
    INCONSISTENT,
    CORRUPTED,
    UNKNOWN
}
