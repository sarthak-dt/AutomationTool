package com.stellar.platform.api.worker;

import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.storage.metadata.ProcessingJobMetadata;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ledger keeps the start written at RUNNING so a finished job has a real window.
 * Previously the finish write replaced the start, every job looked instantaneous, and a
 * reopened project could not show per-source elapsed.
 */
class ProcessingJobExecutionWindowTest {

    private static final Instant T0 = Instant.parse("2026-10-11T10:00:00Z");

    @TempDir
    Path temp;

    private static ProcessingJobMetadata row(JobExecutionStatus status, Instant started, Instant finished) {
        return new ProcessingJobMetadata("job-1", "p", "run-1", "src-a", 1, "dbc", status,
                1, 3, 0d, status.name(), "isolated-worker", T0.minusSeconds(5), started, finished, null, "");
    }

    @Test
    void runningStartsTheWindow() {
        Instant[] window = LocalPlanWorkerEngine.executionWindow(null, JobExecutionStatus.RUNNING, false, T0);
        assertEquals(T0, window[0]);
        assertEquals(null, window[1]);
    }

    @Test
    void terminalWriteKeepsTheStartFromRunning() {
        Instant[] window = LocalPlanWorkerEngine.executionWindow(
                row(JobExecutionStatus.RUNNING, T0, null), JobExecutionStatus.SUCCESS, false, T0.plusSeconds(9));
        assertEquals(T0, window[0]);
        assertEquals(T0.plusSeconds(9), window[1]);
    }

    @Test
    void terminalWriteWithoutRunningRowIsNotAMeasurement() {
        Instant[] window = LocalPlanWorkerEngine.executionWindow(null, JobExecutionStatus.SUCCESS, false, T0);
        assertEquals(window[0], window[1], "no recorded start: the window must stay degenerate, not invented");
    }

    @Test
    void reusedJobKeepsItsEarlierRealWindow() {
        ProcessingJobMetadata earlier = row(JobExecutionStatus.SUCCESS, T0, T0.plusSeconds(12));
        Instant[] window = LocalPlanWorkerEngine.executionWindow(
                earlier, JobExecutionStatus.SUCCESS, true, T0.plusSeconds(500));
        assertEquals(T0, window[0]);
        assertEquals(T0.plusSeconds(12), window[1]);
    }

    @Test
    void measuredElapsedBecomesAWindowWhenRunningStartWasNeverWritten() {
        Instant[] window = LocalPlanWorkerEngine.executionWindow(
                row(JobExecutionStatus.QUEUED, T0, null),
                JobExecutionStatus.SUCCESS,
                false,
                T0.plusSeconds(26),
                java.time.Duration.ofSeconds(8));
        assertEquals(T0.plusSeconds(18), window[0]);
        assertEquals(T0.plusSeconds(26), window[1]);
    }

    @Test
    void leftoverRunningFromAnotherRunDoesNotBecomeTheNewStart() {
        ProcessingJobMetadata staleRunning = new ProcessingJobMetadata(
                "job-1", "p", "run-old", "src-a", 1, "dbc", JobExecutionStatus.RUNNING,
                1, 3, 0d, "RUNNING", "isolated-worker", T0.minusSeconds(1800),
                T0.minusSeconds(1803), null, null, "");
        Instant now = T0.plusSeconds(10);
        Instant[] queued = LocalPlanWorkerEngine.executionWindow(
                staleRunning, JobExecutionStatus.QUEUED, false, now, null, "run-new");
        assertEquals(now, queued[0]);
        Instant[] running = LocalPlanWorkerEngine.executionWindow(
                staleRunning, JobExecutionStatus.RUNNING, false, now, null, "run-new");
        assertEquals(now, running[0]);
        Instant[] done = LocalPlanWorkerEngine.executionWindow(
                new ProcessingJobMetadata("job-1", "p", "run-new", "src-a", 1, "dbc",
                        JobExecutionStatus.RUNNING, 1, 3, 0d, "RUNNING", "isolated-worker",
                        now, now, null, null, ""),
                JobExecutionStatus.SUCCESS, false, now.plusSeconds(3), null, "run-new");
        assertEquals(now, done[0]);
        assertEquals(now.plusSeconds(3), done[1]);
    }

    @Test
    void firstJobRunningThenSuccessCapturesARealWindow() {
        Instant queued = T0;
        Instant running = T0.plusMillis(12);
        Instant doneAt = T0.plusSeconds(3);
        Instant[] start = LocalPlanWorkerEngine.executionWindow(
                row(JobExecutionStatus.QUEUED, queued, null),
                JobExecutionStatus.RUNNING, false, running, null, "run-1");
        assertEquals(running, start[0]);
        Instant[] done = LocalPlanWorkerEngine.executionWindow(
                new ProcessingJobMetadata("job-1", "p", "run-1", "src-a", 1, "dbc",
                        JobExecutionStatus.RUNNING, 1, 3, 0d, "RUNNING", "isolated-worker",
                        running, start[0], null, null, ""),
                JobExecutionStatus.SUCCESS, false, doneAt, java.time.Duration.ofSeconds(3), "run-1");
        assertEquals(running, done[0]);
        assertEquals(doneAt, done[1]);
        assertTrue(done[1].isAfter(done[0]));
    }

    @Test
    void zeroElapsedKeepsASameRunSuccessWindow() {
        ProcessingJobMetadata current = new ProcessingJobMetadata(
                "job-1", "p", "run-1", "src-a", 1, "dbc", JobExecutionStatus.SUCCESS,
                1, 3, 1d, "SUCCESS", "isolated-worker", T0,
                T0, T0.plusSeconds(3), null, "");
        Instant[] window = LocalPlanWorkerEngine.executionWindow(
                current, JobExecutionStatus.SUCCESS, false, T0.plusSeconds(13),
                java.time.Duration.ZERO, "run-1");
        assertEquals(T0, window[0]);
        assertEquals(T0.plusSeconds(3), window[1]);
    }

    @Test
    void zeroElapsedDoesNotKeepAPriorRunSuccessWindow() {
        ProcessingJobMetadata oldSuccess = new ProcessingJobMetadata(
                "job-1", "p", "run-old", "src-a", 1, "dbc", JobExecutionStatus.SUCCESS,
                1, 3, 1d, "SUCCESS", "isolated-worker", T0.minusSeconds(1800),
                T0.minusSeconds(1803), T0, null, "");
        Instant now = T0.plusSeconds(10);
        Instant[] window = LocalPlanWorkerEngine.executionWindow(
                oldSuccess, JobExecutionStatus.SUCCESS, false, now, java.time.Duration.ZERO, "run-new");
        assertEquals(now, window[0]);
        assertEquals(now, window[1]);
    }

    @Test
    void windowSurvivesStoreReopenThroughTheRunningToTerminalTransition() {
        Path db = temp.resolve("ledger.db");
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("p", "P", Instant.now(), Instant.now(), "PROCESSING_RESULT", null));
            store.upsertProcessingJob(row(JobExecutionStatus.QUEUED, T0.minusSeconds(5), null));
            Instant[] running = LocalPlanWorkerEngine.executionWindow(
                    store.findProcessingJob("job-1").orElse(null), JobExecutionStatus.RUNNING, false, T0);
            store.upsertProcessingJob(row(JobExecutionStatus.RUNNING, running[0], running[1]));
            Instant[] done = LocalPlanWorkerEngine.executionWindow(
                    store.findProcessingJob("job-1").orElse(null), JobExecutionStatus.SUCCESS, false, T0.plusSeconds(26));
            store.upsertProcessingJob(row(JobExecutionStatus.SUCCESS, done[0], done[1]));
        }
        try (SqliteProjectMetadataStore reopened = new SqliteProjectMetadataStore(db)) {
            ProcessingJobMetadata found = reopened.findProcessingJob("job-1").orElseThrow();
            assertEquals(T0, found.startedAt());
            assertEquals(T0.plusSeconds(26), found.finishedAt());
            assertTrue(found.finishedAt().isAfter(found.startedAt()));
        }
    }

    @Test
    void firstExecutableJobRunningCapturesStartedAt() {
        Instant queued = T0;
        Instant running = T0.plusMillis(40);
        Instant[] window = LocalPlanWorkerEngine.executionWindow(
                row(JobExecutionStatus.QUEUED, queued, null),
                JobExecutionStatus.RUNNING, false, running, null, "run-1", queued);
        assertEquals(running, window[0]);
        assertEquals(null, window[1]);
    }

    @Test
    void firstJobTerminalUpdatePreservesRunningStartedAt() {
        Instant runStarted = T0;
        Instant running = T0.plusMillis(40);
        Instant doneAt = T0.plusSeconds(3);
        Instant[] window = LocalPlanWorkerEngine.executionWindow(
                new ProcessingJobMetadata("job-1", "p", "run-1", "src-a", 1, "dbc",
                        JobExecutionStatus.RUNNING, 1, 3, 0d, "RUNNING", "isolated-worker",
                        running, running, null, null, ""),
                JobExecutionStatus.SUCCESS_WITH_WARNINGS, false, doneAt,
                java.time.Duration.ZERO, "run-1", runStarted);
        assertEquals(running, window[0]);
        assertEquals(doneAt, window[1]);
        assertTrue(window[1].isAfter(window[0]));
    }

    @Test
    void hydratedZeroElapsedDoesNotClobberSameRunWindow() {
        Instant runStarted = T0;
        ProcessingJobMetadata current = new ProcessingJobMetadata(
                "job-1", "p", "run-1", "src-a", 1, "dbc", JobExecutionStatus.SUCCESS_WITH_WARNINGS,
                1, 3, 1d, "SUCCESS_WITH_WARNINGS", "isolated-worker", T0,
                T0.plusMillis(40), T0.plusSeconds(3), null, "");
        Instant[] window = LocalPlanWorkerEngine.executionWindow(
                current, JobExecutionStatus.SUCCESS_WITH_WARNINGS, false,
                T0.plusSeconds(10), java.time.Duration.ZERO, "run-1", runStarted);
        assertEquals(T0.plusMillis(40), window[0]);
        assertEquals(T0.plusSeconds(3), window[1]);
    }

    @Test
    void historicalThirtyMinuteElapsedCannotStartBeforeTheRun() {
        Instant runStarted = T0.plusSeconds(3600);
        Instant now = runStarted.plusSeconds(10);
        Instant[] window = LocalPlanWorkerEngine.executionWindow(
                row(JobExecutionStatus.QUEUED, runStarted, null),
                JobExecutionStatus.SUCCESS_WITH_WARNINGS, false, now,
                java.time.Duration.ofMinutes(30).plusSeconds(3), "run-1", runStarted);
        assertEquals(now, window[0]);
        assertEquals(now, window[1]);
    }

    @Test
    void staleRunningStartBeforeTheRunIsNotKept() {
        Instant runStarted = T0.plusSeconds(3600);
        Instant stale = runStarted.minusSeconds(1803);
        Instant now = runStarted.plusSeconds(3);
        Instant[] window = LocalPlanWorkerEngine.executionWindow(
                new ProcessingJobMetadata("job-1", "p", "run-1", "src-a", 1, "dbc",
                        JobExecutionStatus.RUNNING, 1, 3, 0d, "RUNNING", "isolated-worker",
                        stale, stale, null, null, ""),
                JobExecutionStatus.SUCCESS, false, now, java.time.Duration.ZERO, "run-1", runStarted);
        assertEquals(now, window[0]);
        assertEquals(now, window[1]);
    }
}
