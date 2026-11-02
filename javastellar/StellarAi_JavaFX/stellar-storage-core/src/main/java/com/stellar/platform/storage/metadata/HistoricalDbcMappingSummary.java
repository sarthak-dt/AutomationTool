package com.stellar.platform.storage.metadata;

/**
 * Historical DBC mapping knowledge derived from confirmed/manual channel_mappings.
 * Supporting evidence for engineers — never an automatic confirmation.
 */
public record HistoricalDbcMappingSummary(
        String definitionId,
        String definitionDisplayHint,
        String dbcFingerprint,
        long useCount,
        long projectCount,
        double averageCoverageRatio
) {
    public HistoricalDbcMappingSummary {
        definitionId = definitionId == null ? "" : definitionId;
        definitionDisplayHint = definitionDisplayHint == null ? "" : definitionDisplayHint;
        dbcFingerprint = dbcFingerprint == null ? "" : dbcFingerprint;
        useCount = Math.max(0L, useCount);
        projectCount = Math.max(0L, projectCount);
    }
}
