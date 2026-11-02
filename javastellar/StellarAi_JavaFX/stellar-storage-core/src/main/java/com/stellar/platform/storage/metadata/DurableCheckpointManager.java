package com.stellar.platform.storage.metadata;

import com.stellar.platform.storage.api.ProcessingLedgerRepository;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Writes committed batches and resume checkpoints in that order: artifact first, then
 * batch metadata, then checkpoint. A checkpoint never points past uncommitted data.
 */
public final class DurableCheckpointManager {

    public static final String ARTIFACT_VERSION = ProcessingArtifactMetadata.ARTIFACT_VERSION_CURRENT;

    private final ProcessingLedgerRepository ledger;

    public DurableCheckpointManager(ProcessingLedgerRepository ledger) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    public ProcessingCheckpointMetadata commitBatch(
            ProcessingBatchMetadata batch,
            ProcessingCheckpointMetadata checkpoint
    ) {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(checkpoint, "checkpoint");
        if (batch.status() != ProcessingBatchStatus.COMMITTED) {
            throw new IllegalArgumentException("Only COMMITTED batches may advance the checkpoint");
        }
        ledger.upsertProcessingBatch(batch);
        ledger.upsertProcessingCheckpoint(checkpoint);
        return checkpoint;
    }

    public Optional<ProcessingCheckpointMetadata> resumableCheckpoint(
            String jobId,
            String sourceFingerprint,
            String dbcFingerprint,
            String planFingerprint,
            String artifactVersion
    ) {
        return ledger.findProcessingCheckpoint(jobId)
                .filter(cp -> cp.isCompatibleWith(sourceFingerprint, dbcFingerprint, planFingerprint, artifactVersion));
    }

    public void invalidate(String jobId) {
        ledger.invalidateProcessingCheckpoint(jobId);
    }

    public void markCompleted(String jobId) {
        ledger.findProcessingCheckpoint(jobId).ifPresent(cp -> ledger.upsertProcessingCheckpoint(
                new ProcessingCheckpointMetadata(
                        cp.checkpointId(),
                        cp.projectId(),
                        cp.jobId(),
                        cp.sourceId(),
                        cp.sourceFingerprint(),
                        cp.dbcFingerprint(),
                        cp.planFingerprint(),
                        cp.artifactVersion(),
                        cp.inputPosition(),
                        cp.batchSequence(),
                        cp.lastTimestampNanos(),
                        cp.cursorPayload(),
                        cp.committedArtifactRef(),
                        ProcessingCheckpointState.COMPLETED,
                        Instant.now())));
    }

    public static ProcessingCheckpointMetadata resumable(
            String projectId,
            String jobId,
            String sourceId,
            String sourceFingerprint,
            String dbcFingerprint,
            String planFingerprint,
            long inputPosition,
            long batchSequence,
            Long lastTimestampNanos,
            String cursor,
            String artifactRef
    ) {
        return new ProcessingCheckpointMetadata(
                null, projectId, jobId, sourceId,
                sourceFingerprint, dbcFingerprint, planFingerprint, ARTIFACT_VERSION,
                inputPosition, batchSequence, lastTimestampNanos, cursor, artifactRef,
                ProcessingCheckpointState.RESUMABLE, Instant.now());
    }
}
