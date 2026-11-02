package com.stellar.platform.parser.mf4.analysis;

import java.util.Objects;

/**
 * A region or block flagged as a recovery candidate (records not recovered yet).
 */
public record MdfRecoveryCandidate(
        long address,
        String reason,
        long physicalBytes
) {

    public MdfRecoveryCandidate {
        Objects.requireNonNull(reason, "reason");
    }
}
