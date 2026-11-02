package com.stellar.platform.storage.metadata;

import com.stellar.platform.domain.processing.ProcessingFailureClass;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Durable job ledger, batches, checkpoints and classified errors.
 *
 * <p>Every assertion reopens the store where survival across restart is the point, because an
 * in-memory pass would prove nothing about crash recovery.</p>
 */
class ProcessingLedgerPersistenceTest {

    private static final String PROJECT = "proj-1";
    private static final String RUN = "run-1";

    @TempDir
    Path temp;

    private Path db;

    @BeforeEach
    void createProject() {
        db = temp.resolve("ledger.db");
        try (SqliteProjectMetadataStore store = open()) {
            store.upsertProject(new ProjectMetadata(
                    PROJECT, "Ledger", Instant.now(), Instant.now(), "PROCESSING_RESULT", null));
        }
    }

    private SqliteProjectMetadataStore open() {
        return new SqliteProjectMetadataStore(db);
    }

    private static ProcessingJobMetadata job(String jobId, JobExecutionStatus status) {
        return new ProcessingJobMetadata(
                jobId, PROJECT, RUN, "src-a", 1, "dbc-a", status,
                0, 3, 0d, "DECODE", "worker-1",
                Instant.parse("2026-09-08T10:00:00Z"), null, null, null, "");
    }

    // --- A1 job ledger ---------------------------------------------------

    @Test
    void jobSurvivesStoreReopen() {
        try (SqliteProjectMetadataStore store = open()) {
            store.upsertProcessingJob(job("job-1", JobExecutionStatus.RUNNING));
        }
        try (SqliteProjectMetadataStore reopened = open()) {
            ProcessingJobMetadata found = reopened.findProcessingJob("job-1").orElseThrow();
            assertEquals(PROJECT, found.projectId());
            assertEquals(RUN, found.runId());
            assertEquals("src-a", found.sourceId());
            assertEquals(1, found.channelId());
            assertEquals("dbc-a", found.definitionId());
            assertEquals(JobExecutionStatus.RUNNING, found.status());
            assertEquals(3, found.maxAttempts());
            assertEquals("worker-1", found.workerId());
        }
    }

    @Test
    void statusTransitionUpdatesInPlaceRatherThanDuplicating() {
        try (SqliteProjectMetadataStore store = open()) {
            store.upsertProcessingJob(job("job-1", JobExecutionStatus.QUEUED));
            store.upsertProcessingJob(new ProcessingJobMetadata(
                    "job-1", PROJECT, RUN, "src-a", 1, "dbc-a", JobExecutionStatus.SUCCESS_WITH_WARNINGS,
                    1, 3, 1d, "COMPLETE", "worker-1",
                    Instant.parse("2026-09-08T10:00:00Z"),
                    Instant.parse("2026-09-08T10:00:05Z"),
                    Instant.parse("2026-09-08T10:04:00Z"),
                    null, "2 signals had gaps"));

            assertEquals(1, store.listProcessingJobs(PROJECT).size());
            ProcessingJobMetadata found = store.findProcessingJob("job-1").orElseThrow();
            assertEquals(JobExecutionStatus.SUCCESS_WITH_WARNINGS, found.status());
            assertEquals(1d, found.progress());
            assertEquals("2 signals had gaps", found.message());
            assertEquals(Instant.parse("2026-09-08T10:04:00Z"), found.finishedAt());
        }
    }

    @Test
    void unfinishedJobsAreDiscoverableAfterRestartButTerminalOnesAreNot() {
        try (SqliteProjectMetadataStore store = open()) {
            store.upsertProcessingJob(job("running", JobExecutionStatus.RUNNING));
            store.upsertProcessingJob(new ProcessingJobMetadata(
                    "queued", PROJECT, RUN, "src-b", 1, "dbc-a", JobExecutionStatus.QUEUED,
                    0, 3, 0d, "", "", Instant.now(), null, null, null, ""));
            store.upsertProcessingJob(new ProcessingJobMetadata(
                    "done", PROJECT, RUN, "src-c", 1, "dbc-a", JobExecutionStatus.SUCCESS,
                    1, 3, 1d, "", "", Instant.now(), null, Instant.now(), null, ""));
            store.upsertProcessingJob(new ProcessingJobMetadata(
                    "cancelled", PROJECT, RUN, "src-d", 1, "dbc-a", JobExecutionStatus.CANCELLED,
                    1, 3, 0.3d, "", "", Instant.now(), null, Instant.now(), null, ""));
        }
        try (SqliteProjectMetadataStore reopened = open()) {
            List<String> unfinished = reopened.listUnfinishedProcessingJobs(PROJECT).stream()
                    .map(ProcessingJobMetadata::jobId).sorted().toList();
            assertEquals(List.of("queued", "running"), unfinished);
        }
    }

    @Test
    void retryIsOnlyOfferedForRetryableClassesWithinAttemptBudget() {
        ProcessingJobMetadata transientFailure = new ProcessingJobMetadata(
                "j", PROJECT, RUN, "src-a", 1, "d", JobExecutionStatus.FAILED,
                1, 3, 0d, "", "", Instant.now(), null, Instant.now(),
                ProcessingFailureClass.TRANSIENT_IO, "");
        assertTrue(transientFailure.canRetry());

        ProcessingJobMetadata corrupt = new ProcessingJobMetadata(
                "j", PROJECT, RUN, "src-a", 1, "d", JobExecutionStatus.FAILED,
                1, 3, 0d, "", "", Instant.now(), null, Instant.now(),
                ProcessingFailureClass.CORRUPT_INPUT, "");
        assertFalse(corrupt.canRetry(), "corrupt input must never be retried automatically");

        ProcessingJobMetadata exhausted = new ProcessingJobMetadata(
                "j", PROJECT, RUN, "src-a", 1, "d", JobExecutionStatus.FAILED,
                3, 3, 0d, "", "", Instant.now(), null, Instant.now(),
                ProcessingFailureClass.TRANSIENT_IO, "");
        assertFalse(exhausted.canRetry(), "attempt budget must cap retries");
    }

    // --- A2 batches ------------------------------------------------------

    @Test
    void lastCommittedBatchIgnoresOpenAndAbandonedBatches() {
        try (SqliteProjectMetadataStore store = open()) {
            store.upsertProcessingJob(job("job-1", JobExecutionStatus.RUNNING));
            store.upsertProcessingBatch(batch("b0", 0, ProcessingBatchStatus.COMMITTED));
            store.upsertProcessingBatch(batch("b1", 1, ProcessingBatchStatus.COMMITTED));
            store.upsertProcessingBatch(batch("b2", 2, ProcessingBatchStatus.ABANDONED));
            store.upsertProcessingBatch(batch("b3", 3, ProcessingBatchStatus.OPEN));
        }
        try (SqliteProjectMetadataStore reopened = open()) {
            ProcessingBatchMetadata last = reopened.findLastCommittedBatch("job-1").orElseThrow();
            assertEquals(1L, last.sequence());
            assertTrue(last.isCommitted());
            assertEquals(4, reopened.listProcessingBatches("job-1").size());
        }
    }

    @Test
    void batchesReadBackInSequenceOrder() {
        try (SqliteProjectMetadataStore store = open()) {
            store.upsertProcessingJob(job("job-1", JobExecutionStatus.RUNNING));
            store.upsertProcessingBatch(batch("b2", 2, ProcessingBatchStatus.COMMITTED));
            store.upsertProcessingBatch(batch("b0", 0, ProcessingBatchStatus.COMMITTED));
            store.upsertProcessingBatch(batch("b1", 1, ProcessingBatchStatus.COMMITTED));

            assertEquals(List.of(0L, 1L, 2L), store.listProcessingBatches("job-1").stream()
                    .map(ProcessingBatchMetadata::sequence).toList());
        }
    }

    private static ProcessingBatchMetadata batch(String id, long sequence, ProcessingBatchStatus status) {
        return new ProcessingBatchMetadata(
                id, PROJECT, "job-1", sequence, sequence * 1000, (sequence + 1) * 1000,
                500, 2500, status, "ref/" + sequence, Instant.now(),
                status == ProcessingBatchStatus.COMMITTED ? Instant.now() : null);
    }

    // --- A3 checkpoints --------------------------------------------------

    @Test
    void checkpointSurvivesReopenAndKeepsOnlyTheLatestPerJob() {
        try (SqliteProjectMetadataStore store = open()) {
            store.upsertProcessingJob(job("job-1", JobExecutionStatus.RUNNING));
            store.upsertProcessingCheckpoint(checkpoint(1L, 1000L));
            store.upsertProcessingCheckpoint(checkpoint(7L, 7000L));
        }
        try (SqliteProjectMetadataStore reopened = open()) {
            ProcessingCheckpointMetadata found = reopened.findProcessingCheckpoint("job-1").orElseThrow();
            assertEquals(7L, found.batchSequence());
            assertEquals(7000L, found.inputPosition());
            assertEquals("cursor-7", found.cursorPayload());
            assertSame(ProcessingCheckpointState.RESUMABLE, found.state());
        }
    }

    @Test
    void checkpointIsReusableOnlyWhenEveryIdentityComponentMatches() {
        ProcessingCheckpointMetadata cp = checkpoint(3L, 3000L);

        assertTrue(cp.isCompatibleWith("src-fp", "dbc-fp", "plan-fp", "1"));
        assertFalse(cp.isCompatibleWith("CHANGED", "dbc-fp", "plan-fp", "1"), "source changed");
        assertFalse(cp.isCompatibleWith("src-fp", "CHANGED", "plan-fp", "1"), "definition changed");
        assertFalse(cp.isCompatibleWith("src-fp", "dbc-fp", "CHANGED", "1"), "plan changed");
        assertFalse(cp.isCompatibleWith("src-fp", "dbc-fp", "plan-fp", "2"), "artifact format changed");
    }

    @Test
    void invalidatedCheckpointIsNeverResumable() {
        try (SqliteProjectMetadataStore store = open()) {
            store.upsertProcessingJob(job("job-1", JobExecutionStatus.RUNNING));
            store.upsertProcessingCheckpoint(checkpoint(3L, 3000L));
            store.invalidateProcessingCheckpoint("job-1");
        }
        try (SqliteProjectMetadataStore reopened = open()) {
            ProcessingCheckpointMetadata found = reopened.findProcessingCheckpoint("job-1").orElseThrow();
            assertSame(ProcessingCheckpointState.INVALIDATED, found.state());
            assertFalse(found.isCompatibleWith("src-fp", "dbc-fp", "plan-fp", "1"),
                    "an invalidated checkpoint must be rejected even when fingerprints still match");
        }
    }

    @Test
    void missingCheckpointIsEmptyRatherThanAnError() {
        try (SqliteProjectMetadataStore store = open()) {
            assertEquals(Optional.empty(), store.findProcessingCheckpoint("no-such-job"));
        }
    }

    private static ProcessingCheckpointMetadata checkpoint(long sequence, long position) {
        return new ProcessingCheckpointMetadata(
                null, PROJECT, "job-1", "src-a",
                "src-fp", "dbc-fp", "plan-fp", "1",
                position, sequence, 123_456_789L, "cursor-" + sequence,
                "artifact/ref", ProcessingCheckpointState.RESUMABLE, Instant.now());
    }

    // --- A4 errors -------------------------------------------------------

    @Test
    void classifiedErrorsPersistWithRetryFlagAndReason() {
        try (SqliteProjectMetadataStore store = open()) {
            store.upsertProcessingJob(job("job-1", JobExecutionStatus.FAILED));
            store.appendProcessingError(ProcessingErrorMetadata.of(
                    PROJECT, RUN, "job-1", "src-a", ProcessingFailureClass.WORKER_CRASH,
                    "Worker stopped unexpectedly", "exit code 1"));
            store.appendProcessingError(ProcessingErrorMetadata.of(
                    PROJECT, RUN, "job-1", "src-a", ProcessingFailureClass.CORRUPT_INPUT,
                    "Unreadable data block", "block 42 truncated"));
        }
        try (SqliteProjectMetadataStore reopened = open()) {
            List<ProcessingErrorMetadata> errors = reopened.listProcessingErrors(PROJECT);
            assertEquals(2, errors.size());

            ProcessingErrorMetadata crash = errors.stream()
                    .filter(e -> e.classification() == ProcessingFailureClass.WORKER_CRASH)
                    .findFirst().orElseThrow();
            assertTrue(crash.retryable());
            assertEquals("exit code 1", crash.technicalDetail());

            ProcessingErrorMetadata corrupt = errors.stream()
                    .filter(e -> e.classification() == ProcessingFailureClass.CORRUPT_INPUT)
                    .findFirst().orElseThrow();
            assertFalse(corrupt.retryable());
        }
    }

    @Test
    void unknownPersistedClassificationDoesNotBreakReading() {
        assertSame(ProcessingFailureClass.INTERNAL_ERROR,
                ProcessingFailureClass.parse("SOME_FUTURE_CLASS", ProcessingFailureClass.INTERNAL_ERROR));
    }
}
