package com.stellar.platform.parser.mf4.recovery;

/**
 * Outcome of MF4 recovery analysis for a data group / file region.
 */
public enum MdfRecoveryStatus {
    NOT_REQUIRED,
    RECOVERED,
    PARTIALLY_RECOVERED,
    NOT_RECOVERABLE,
    FATAL
}
