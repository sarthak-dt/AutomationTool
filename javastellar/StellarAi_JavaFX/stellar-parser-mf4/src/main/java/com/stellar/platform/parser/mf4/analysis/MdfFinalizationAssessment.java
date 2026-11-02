package com.stellar.platform.parser.mf4.analysis;

import java.util.List;
import java.util.Objects;

/**
 * Finalization assessment with supporting evidence (no record recovery).
 */
public record MdfFinalizationAssessment(
        MdfFinalizationStatus status,
        long declaredCycleCountTotal,
        Long possibleRecordCount,
        long physicalDataRegionLength,
        boolean recoveryCandidate,
        List<String> reasons
) {

    public MdfFinalizationAssessment {
        Objects.requireNonNull(status, "status");
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
    }
}
