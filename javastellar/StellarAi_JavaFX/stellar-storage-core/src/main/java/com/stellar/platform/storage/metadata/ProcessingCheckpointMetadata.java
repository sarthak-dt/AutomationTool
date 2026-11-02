package com.stellar.platform.storage.metadata;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Latest durable resume point for one job.
 *
 * <p>A checkpoint always refers to a committed batch boundary: {@code batchSequence} is the last
 * batch whose artifact segment was flushed, and {@code inputPosition} is where the next batch must
 * start. It must never point past data that was not committed, otherwise a resume would silently
 * skip samples.</p>
 *
 * <p>{@code cursorPayload} is an opaque, parser-supplied string. Storage never interprets it, which
 * keeps the schema generic across MF4/ASC/BLF.</p>
 */
public record ProcessingCheckpointMetadata(
        String checkpointId,
        String projectId,
        String jobId,
        String sourceId,
        String sourceFingerprint,
        String dbcFingerprint,
        String planFingerprint,
        String artifactVersion,
        long inputPosition,
        long batchSequence,
        Long lastTimestampNanos,
        String cursorPayload,
        String committedArtifactRef,
        ProcessingCheckpointState state,
        Instant createdAt
) {
    public ProcessingCheckpointMetadata {
        checkpointId = checkpointId == null || checkpointId.isBlank()
                ? UUID.randomUUID().toString() : checkpointId;
        projectId = require(projectId, "projectId");
        jobId = require(jobId, "jobId");
        sourceId = require(sourceId, "sourceId");
        sourceFingerprint = sourceFingerprint == null ? "" : sourceFingerprint;
        dbcFingerprint = dbcFingerprint == null ? "" : dbcFingerprint;
        planFingerprint = planFingerprint == null ? "" : planFingerprint;
        artifactVersion = artifactVersion == null || artifactVersion.isBlank()
                ? ProcessingArtifactMetadata.ARTIFACT_VERSION_V1 : artifactVersion;
        inputPosition = Math.max(0L, inputPosition);
        batchSequence = Math.max(0L, batchSequence);
        cursorPayload = cursorPayload == null ? "" : cursorPayload;
        committedArtifactRef = committedArtifactRef == null ? "" : committedArtifactRef;
        state = Objects.requireNonNullElse(state, ProcessingCheckpointState.RESUMABLE);
        createdAt = Objects.requireNonNullElseGet(createdAt, Instant::now);
    }

    /**
     * A checkpoint is only safe to resume when every identity component still matches. Any change
     * to the source, the definition, the plan or the artifact format means the already-committed
     * samples were produced under different rules, so resuming would corrupt the result.
     */
    public boolean isCompatibleWith(
            String currentSourceFingerprint,
            String currentDbcFingerprint,
            String currentPlanFingerprint,
            String currentArtifactVersion
    ) {
        return state == ProcessingCheckpointState.RESUMABLE
                && sourceFingerprint.equals(nullToEmpty(currentSourceFingerprint))
                && dbcFingerprint.equals(nullToEmpty(currentDbcFingerprint))
                && planFingerprint.equals(nullToEmpty(currentPlanFingerprint))
                && artifactVersion.equals(nullToEmpty(currentArtifactVersion));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
