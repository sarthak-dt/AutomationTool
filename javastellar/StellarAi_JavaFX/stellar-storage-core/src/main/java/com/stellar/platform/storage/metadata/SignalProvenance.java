package com.stellar.platform.storage.metadata;

/**
 * Engineering audit trail: how a catalogued signal was produced.
 * Assembled from signal_catalog + processing_artifacts + processing_runs + project_files.
 */
public record SignalProvenance(
        String signalCatalogId,
        String signalName,
        String messageName,
        long canId,
        String unit,
        String projectId,
        String sourceId,
        String sourcePath,
        String sourceFileName,
        String sourceFingerprint,
        int channelId,
        String definitionId,
        String definitionPath,
        String definitionFileName,
        String dbcFingerprint,
        String mappingLifecycle,
        String processingRunId,
        String processingRunStatus,
        String artifactId,
        String artifactStorageRef,
        String artifactVersion,
        long sampleCount,
        Long startTimestampNanos,
        Long endTimestampNanos
) {
}
