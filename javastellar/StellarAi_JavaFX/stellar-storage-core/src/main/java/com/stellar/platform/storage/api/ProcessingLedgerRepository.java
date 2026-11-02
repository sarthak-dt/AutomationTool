package com.stellar.platform.storage.api;

import com.stellar.platform.storage.metadata.ProcessingBatchMetadata;
import com.stellar.platform.storage.metadata.ProcessingCheckpointMetadata;
import com.stellar.platform.storage.metadata.ProcessingErrorMetadata;
import com.stellar.platform.storage.metadata.ProcessingJobMetadata;

import java.util.List;
import java.util.Optional;

/**
 * Durable processing lifecycle: the job ledger, committed batches, resume checkpoints and
 * classified errors.
 *
 * <p>Separate from {@link ProcessingRepository}, which owns run summaries and artifact manifests.
 * Defaults are inert so alternative stores stay compilable; the SQLite store is the real
 * implementation.</p>
 */
public interface ProcessingLedgerRepository {

    default void upsertProcessingJob(ProcessingJobMetadata job) {
    }

    default Optional<ProcessingJobMetadata> findProcessingJob(String jobId) {
        return Optional.empty();
    }

    default List<ProcessingJobMetadata> listProcessingJobs(String projectId) {
        return List.of();
    }

    /**
     * Jobs a previous session left in a non-terminal state. Restart recovery uses this to mark
     * them INTERRUPTED and decide whether a compatible checkpoint allows a resume.
     */
    default List<ProcessingJobMetadata> listUnfinishedProcessingJobs(String projectId) {
        return List.of();
    }

    default void upsertProcessingBatch(ProcessingBatchMetadata batch) {
    }

    default List<ProcessingBatchMetadata> listProcessingBatches(String jobId) {
        return List.of();
    }

    /** Highest committed batch for the job, which is the furthest safe resume boundary. */
    default Optional<ProcessingBatchMetadata> findLastCommittedBatch(String jobId) {
        return Optional.empty();
    }

    /** Replaces the single resume point held for the job. */
    default void upsertProcessingCheckpoint(ProcessingCheckpointMetadata checkpoint) {
    }

    default Optional<ProcessingCheckpointMetadata> findProcessingCheckpoint(String jobId) {
        return Optional.empty();
    }

    /** Marks the checkpoint unusable without deleting the audit of its existence. */
    default void invalidateProcessingCheckpoint(String jobId) {
    }

    default void appendProcessingError(ProcessingErrorMetadata error) {
    }

    default List<ProcessingErrorMetadata> listProcessingErrors(String projectId) {
        return List.of();
    }
}
