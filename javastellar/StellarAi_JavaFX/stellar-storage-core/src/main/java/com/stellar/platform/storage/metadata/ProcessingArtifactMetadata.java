package com.stellar.platform.storage.metadata;

import java.time.Instant;
import java.util.Objects;

/**
 * Manifest row for a project-scoped lossless signal artifact — also the durable job record.
 * <p>
 * Intentionally stores only references and fingerprints — never waveform samples.
 * Prefer {@link #artifactStorageRef()} (portable); {@link #artifactPath()} remains for
 * schema-v3 absolute paths and local resolution cache.
 * <p>
 * Field mapping for durable jobs:
 * <ul>
 *   <li>{@code artifactId} → jobId</li>
 *   <li>{@code processingRunId} → runId</li>
 *   <li>{@code jobExecutionStatus}/{@code diagnosticsSummary}/{@code technicalDetail}
 *       preserve warning/failure reasons across reopen without reprocessing</li>
 *   <li>{@code createdAt} → completion / commit time</li>
 * </ul>
 */
public record ProcessingArtifactMetadata(
        String artifactId,
        String projectId,
        String sourceId,
        int channelId,
        String definitionId,
        String processingRunId,
        String artifactType,
        String artifactPath,
        String artifactStorageRef,
        String artifactVersion,
        String sourceFingerprint,
        String dbcFingerprint,
        String planFingerprint,
        long signalCount,
        long sampleCount,
        Long startTimestampNanos,
        Long endTimestampNanos,
        String status,
        Instant createdAt,
        String mappingLifecycle,
        String jobExecutionStatus,
        String diagnosticsSummary,
        String technicalDetail
) {
    public static final String TYPE_LOSSLESS_SIGNAL_STORE = "LOSSLESS_SIGNAL_STORE";
    public static final String STATUS_VALID = "VALID";
    public static final String STATUS_STALE = "STALE";
    public static final String STATUS_MISSING = "MISSING";
    /** Manifest format version for lossless signal stores written before per-sample validity. */
    public static final String ARTIFACT_VERSION_V1 = "1";
    /**
     * Stores a validity byte with each sample. Older manifests stay {@code "1"} and must be
     * reprocessed; they are not promoted and their flags are not invented.
     */
    public static final String ARTIFACT_VERSION_V2 = "2";
    /**
     * Same per-sample validity layout as {@link #ARTIFACT_VERSION_V2}. This generation applies
     * MDF 3 channel conversions. Version 2 stores are not rewritten; they are stale until reprocessed.
     */
    public static final String ARTIFACT_VERSION_V3 = "3";
    public static final String ARTIFACT_VERSION_CURRENT = ARTIFACT_VERSION_V3;

    /** Backward-compatible constructor used by schema-v3 writers/tests. */
    public ProcessingArtifactMetadata(
            String artifactId,
            String projectId,
            String sourceId,
            int channelId,
            String definitionId,
            String processingRunId,
            String artifactType,
            String artifactPath,
            String artifactVersion,
            String sourceFingerprint,
            String dbcFingerprint,
            long signalCount,
            long sampleCount,
            Long startTimestampNanos,
            Long endTimestampNanos,
            String status,
            Instant createdAt,
            String mappingLifecycle
    ) {
        this(artifactId, projectId, sourceId, channelId, definitionId, processingRunId, artifactType,
                artifactPath, "", artifactVersion, sourceFingerprint, dbcFingerprint, "",
                signalCount, sampleCount, startTimestampNanos, endTimestampNanos, status, createdAt,
                mappingLifecycle, "", "", "");
    }

    /** Schema-v4..v8 constructor with portable storage ref, without job outcome / plan fingerprint. */
    public ProcessingArtifactMetadata(
            String artifactId,
            String projectId,
            String sourceId,
            int channelId,
            String definitionId,
            String processingRunId,
            String artifactType,
            String artifactPath,
            String artifactStorageRef,
            String artifactVersion,
            String sourceFingerprint,
            String dbcFingerprint,
            long signalCount,
            long sampleCount,
            Long startTimestampNanos,
            Long endTimestampNanos,
            String status,
            Instant createdAt,
            String mappingLifecycle
    ) {
        this(artifactId, projectId, sourceId, channelId, definitionId, processingRunId, artifactType,
                artifactPath, artifactStorageRef, artifactVersion, sourceFingerprint, dbcFingerprint, "",
                signalCount, sampleCount, startTimestampNanos, endTimestampNanos, status, createdAt,
                mappingLifecycle, "", "", "");
    }

    /** Schema-v9 constructor with job outcome fields, without plan fingerprint. */
    public ProcessingArtifactMetadata(
            String artifactId,
            String projectId,
            String sourceId,
            int channelId,
            String definitionId,
            String processingRunId,
            String artifactType,
            String artifactPath,
            String artifactStorageRef,
            String artifactVersion,
            String sourceFingerprint,
            String dbcFingerprint,
            long signalCount,
            long sampleCount,
            Long startTimestampNanos,
            Long endTimestampNanos,
            String status,
            Instant createdAt,
            String mappingLifecycle,
            String jobExecutionStatus,
            String diagnosticsSummary,
            String technicalDetail
    ) {
        this(artifactId, projectId, sourceId, channelId, definitionId, processingRunId, artifactType,
                artifactPath, artifactStorageRef, artifactVersion, sourceFingerprint, dbcFingerprint, "",
                signalCount, sampleCount, startTimestampNanos, endTimestampNanos, status, createdAt,
                mappingLifecycle, jobExecutionStatus, diagnosticsSummary, technicalDetail);
    }

    public ProcessingArtifactMetadata {
        artifactId = requireText(artifactId, "artifactId");
        projectId = requireText(projectId, "projectId");
        sourceId = requireText(sourceId, "sourceId");
        if (channelId < 0) throw new IllegalArgumentException("channelId must be >= 0");
        definitionId = definitionId == null ? "" : definitionId;
        processingRunId = processingRunId == null ? "" : processingRunId;
        artifactType = artifactType == null || artifactType.isBlank() ? TYPE_LOSSLESS_SIGNAL_STORE : artifactType;
        artifactPath = artifactPath == null ? "" : artifactPath;
        artifactStorageRef = artifactStorageRef == null ? "" : artifactStorageRef;
        if (artifactPath.isBlank() && artifactStorageRef.isBlank()) {
            throw new IllegalArgumentException("artifactPath or artifactStorageRef is required");
        }
        artifactVersion = artifactVersion == null || artifactVersion.isBlank()
                ? ARTIFACT_VERSION_V1 : artifactVersion;
        sourceFingerprint = sourceFingerprint == null ? "" : sourceFingerprint;
        dbcFingerprint = dbcFingerprint == null ? "" : dbcFingerprint;
        planFingerprint = planFingerprint == null ? "" : planFingerprint;
        signalCount = Math.max(0L, signalCount);
        sampleCount = Math.max(0L, sampleCount);
        status = status == null || status.isBlank() ? STATUS_VALID : status;
        createdAt = Objects.requireNonNullElseGet(createdAt, Instant::now);
        mappingLifecycle = mappingLifecycle == null ? "" : mappingLifecycle;
        jobExecutionStatus = jobExecutionStatus == null ? "" : jobExecutionStatus;
        diagnosticsSummary = diagnosticsSummary == null ? "" : diagnosticsSummary;
        technicalDetail = technicalDetail == null ? "" : technicalDetail;
    }

    /** Prefer portable storage ref; fall back to absolute path for legacy rows. */
    public String resolutionKey() {
        return !artifactStorageRef.isBlank() ? artifactStorageRef : artifactPath;
    }

    /** Durable job identity (same as {@link #artifactId()}). */
    public String jobId() {
        return artifactId;
    }

    public ProcessingArtifactMetadata withStatus(String newStatus) {
        return new ProcessingArtifactMetadata(
                artifactId, projectId, sourceId, channelId, definitionId, processingRunId,
                artifactType, artifactPath, artifactStorageRef, artifactVersion,
                sourceFingerprint, dbcFingerprint, planFingerprint,
                signalCount, sampleCount, startTimestampNanos, endTimestampNanos,
                newStatus, Instant.now(), mappingLifecycle,
                jobExecutionStatus, diagnosticsSummary, technicalDetail
        );
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
