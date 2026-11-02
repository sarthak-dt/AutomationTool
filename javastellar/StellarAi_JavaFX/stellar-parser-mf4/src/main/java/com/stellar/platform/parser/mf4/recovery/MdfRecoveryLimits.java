package com.stellar.platform.parser.mf4.recovery;

/**
 * Conservative safeguards against runaway recovery / false-positive resynchronization.
 */
public record MdfRecoveryLimits(
        int maxConsecutiveInvalidRecords,
        int maxResyncAttempts,
        int maxSkippedBytesPerResync
) {

    public static final MdfRecoveryLimits DEFAULT = new MdfRecoveryLimits(3, 8, 256);

    public MdfRecoveryLimits {
        if (maxConsecutiveInvalidRecords < 1) {
            throw new IllegalArgumentException("maxConsecutiveInvalidRecords must be >= 1");
        }
        if (maxResyncAttempts < 0) {
            throw new IllegalArgumentException("maxResyncAttempts must be >= 0");
        }
        if (maxSkippedBytesPerResync < 0) {
            throw new IllegalArgumentException("maxSkippedBytesPerResync must be >= 0");
        }
    }
}
