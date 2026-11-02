package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.storage.metadata.ProcessingJobMetadata;
import com.stellar.platform.storage.metadata.ProcessingRunMetadata;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reprocess Entire Project and Add More Measurement elapsed reconstruction.
 * Job identity is source+channel; run_id / started_at / finished_at already exist.
 */
class ProcessingJobTimingReprocessIncrementalTest {

    private static final Instant T0 = Instant.parse("2026-10-11T10:00:00Z");
    private static final String RUN1 = "run-1";
    private static final String RUN2 = "run-2";
    private static final String RUN3 = "run-3";

    @TempDir
    Path temp;

    private static ProcessingJobMetadata job(
            String id, String runId, String source, JobExecutionStatus status, Instant started, Instant finished
    ) {
        return new ProcessingJobMetadata(id, "p", runId, source, 1, "dbc", status,
                1, 3, 1d, status.name(), "isolated-worker", T0, started, finished, null, "");
    }

    private static ProcessingRunMetadata run(String runId, Instant started, Instant finished) {
        return new ProcessingRunMetadata(runId, "p", "COMPLETED", false, 3, started, finished, "ok");
    }

    private static String label(Map<String, ProcessingJobTiming.SourceWindow> windows, String source) {
        ProcessingJobTiming.SourceWindow window = windows.get(source);
        return ProcessingElapsed.format(window == null ? Optional.empty() : window.elapsed());
    }

    @Test
    void initialProcessElapsedIsSavedAndReopened() {
        List<ProcessingJobMetadata> rows = List.of(
                job("a", RUN1, "src-a", JobExecutionStatus.SUCCESS, T0, T0.plusSeconds(9)),
                job("b", RUN1, "src-b", JobExecutionStatus.SUCCESS, T0.plusSeconds(9), T0.plusSeconds(20)),
                job("c", RUN1, "src-c", JobExecutionStatus.SUCCESS, T0.plusSeconds(20), T0.plusSeconds(26)));
        Map<String, ProcessingJobTiming.SourceWindow> windows =
                ProcessingJobTiming.sourceWindows(rows, List.of(run(RUN1, T0, T0.plusSeconds(26))));
        assertEquals("00:09", label(windows, "src-a"));
        assertEquals("00:11", label(windows, "src-b"));
        assertEquals("00:06", label(windows, "src-c"));
    }

    @Test
    void fullReprocessReplacesOldElapsedAndSurvivesReopen() {
        Path db = temp.resolve("reprocess.db");
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("p", "test", T0, T0, "PROCESSING_RESULT", null));
            store.upsertProcessingRun(run(RUN1, T0, T0.plusSeconds(26)));
            store.upsertProcessingJob(job("a", RUN1, "src-a", JobExecutionStatus.SUCCESS, T0, T0.plusSeconds(9)));
            store.upsertProcessingJob(job("b", RUN1, "src-b", JobExecutionStatus.SUCCESS, T0.plusSeconds(9), T0.plusSeconds(26)));
            store.upsertProcessingRun(run(RUN2, T0.plusSeconds(60), T0.plusSeconds(68)));
            store.upsertProcessingJob(job("a", RUN2, "src-a", JobExecutionStatus.SUCCESS,
                    T0.plusSeconds(60), T0.plusSeconds(64)));
            store.upsertProcessingJob(job("b", RUN2, "src-b", JobExecutionStatus.SUCCESS,
                    T0.plusSeconds(64), T0.plusSeconds(68)));
        }
        try (SqliteProjectMetadataStore reopened = new SqliteProjectMetadataStore(db)) {
            Map<String, ProcessingJobTiming.SourceWindow> windows = ProcessingJobTiming.sourceWindows(
                    reopened.listProcessingJobs("p"),
                    List.of(run(RUN2, T0.plusSeconds(60), T0.plusSeconds(68)),
                            run(RUN1, T0, T0.plusSeconds(26))));
            assertEquals("00:04", label(windows, "src-a"), "reprocess must replace the old 00:09");
            assertEquals("00:04", label(windows, "src-b"), "reprocess must replace the old 00:17");
            assertEquals("00:08", ProcessingElapsed.format(ProcessingElapsed.totalElapsed(
                    Duration.ofSeconds(8), T0.plusSeconds(60), T0.plusSeconds(68))));
        }
    }

    @Test
    void staleQueuedDoesNotMaskNewerSuccess() {
        List<ProcessingJobMetadata> rows = List.of(
                job("queued", RUN2, "src-a", JobExecutionStatus.QUEUED, T0.plusSeconds(60), null),
                job("done", RUN2, "src-a", JobExecutionStatus.SUCCESS, T0.plusSeconds(62), T0.plusSeconds(69)));
        Map<String, ProcessingJobTiming.SourceWindow> windows =
                ProcessingJobTiming.sourceWindows(rows, List.of(run(RUN2, T0.plusSeconds(60), T0.plusSeconds(69))));
        assertEquals("00:07", label(windows, "src-a"));
    }

    @Test
    void zeroDurationOldRowDoesNotOverrideRealNewTiming() {
        List<ProcessingJobMetadata> rows = List.of(
                job("old", RUN1, "src-a", JobExecutionStatus.SUCCESS, T0.plusSeconds(9), T0.plusSeconds(9)),
                job("new", RUN2, "src-a", JobExecutionStatus.SUCCESS, T0.plusSeconds(60), T0.plusSeconds(65)));
        Map<String, ProcessingJobTiming.SourceWindow> windows = ProcessingJobTiming.sourceWindows(
                rows, List.of(run(RUN2, T0.plusSeconds(60), T0.plusSeconds(65)), run(RUN1, T0, T0.plusSeconds(26))));
        assertEquals("00:05", label(windows, "src-a"));
        assertFalse(ProcessingJobTiming.retainExistingSuccessfulWindow(
                rows.get(0), JobExecutionStatus.QUEUED, true),
                "force reprocess must supersede a prior window");
    }

    @Test
    void addFilesKeepsOldElapsedAndAssignsNewFileElapsed() {
        List<ProcessingJobMetadata> rows = List.of(
                job("a", RUN1, "src-a", JobExecutionStatus.SUCCESS, T0, T0.plusSeconds(9)),
                job("b", RUN1, "src-b", JobExecutionStatus.SUCCESS, T0.plusSeconds(9), T0.plusSeconds(20)),
                job("c", RUN1, "src-c", JobExecutionStatus.SUCCESS, T0.plusSeconds(20), T0.plusSeconds(26)),
                job("d", RUN2, "src-d", JobExecutionStatus.SUCCESS, T0.plusSeconds(40), T0.plusSeconds(43)));
        Map<String, ProcessingJobTiming.SourceWindow> windows = ProcessingJobTiming.sourceWindows(
                rows, List.of(run(RUN2, T0.plusSeconds(40), T0.plusSeconds(43)), run(RUN1, T0, T0.plusSeconds(26))));
        assertEquals("00:09", label(windows, "src-a"));
        assertEquals("00:11", label(windows, "src-b"));
        assertEquals("00:06", label(windows, "src-c"));
        assertEquals("00:03", label(windows, "src-d"));
        assertEquals("00:03", ProcessingElapsed.format(ProcessingElapsed.totalElapsed(
                Duration.ofSeconds(3), T0.plusSeconds(40), T0.plusSeconds(43))),
                "incremental total is the add-files run only");
    }

    @Test
    void incrementalSaveReopenAndSecondAddKeepEverySource() {
        Path db = temp.resolve("incremental.db");
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("p", "test", T0, T0, "PROCESSING_RESULT", null));
            store.upsertProcessingRun(run(RUN1, T0, T0.plusSeconds(26)));
            store.upsertProcessingJob(job("a", RUN1, "src-a", JobExecutionStatus.SUCCESS, T0, T0.plusSeconds(9)));
            store.upsertProcessingJob(job("b", RUN1, "src-b", JobExecutionStatus.SUCCESS, T0.plusSeconds(9), T0.plusSeconds(20)));
            store.upsertProcessingJob(job("c", RUN1, "src-c", JobExecutionStatus.SUCCESS, T0.plusSeconds(20), T0.plusSeconds(26)));
            store.upsertProcessingRun(run(RUN2, T0.plusSeconds(40), T0.plusSeconds(43)));
            store.upsertProcessingJob(job("d", RUN2, "src-d", JobExecutionStatus.SUCCESS,
                    T0.plusSeconds(40), T0.plusSeconds(43)));
            store.upsertProcessingRun(run(RUN3, T0.plusSeconds(80), T0.plusSeconds(85)));
            store.upsertProcessingJob(job("e", RUN3, "src-e", JobExecutionStatus.SUCCESS,
                    T0.plusSeconds(80), T0.plusSeconds(85)));
        }
        try (SqliteProjectMetadataStore reopened = new SqliteProjectMetadataStore(db)) {
            Map<String, ProcessingJobTiming.SourceWindow> windows = ProcessingJobTiming.sourceWindows(
                    reopened.listProcessingJobs("p"),
                    List.of(run(RUN3, T0.plusSeconds(80), T0.plusSeconds(85)),
                            run(RUN2, T0.plusSeconds(40), T0.plusSeconds(43)),
                            run(RUN1, T0, T0.plusSeconds(26))));
            assertEquals("00:09", label(windows, "src-a"));
            assertEquals("00:11", label(windows, "src-b"));
            assertEquals("00:06", label(windows, "src-c"));
            assertEquals("00:03", label(windows, "src-d"));
            assertEquals("00:05", label(windows, "src-e"));
        }
    }

    @Test
    void noCrossSourceOrCrossRunLeakage() {
        List<ProcessingJobMetadata> rows = List.of(
                job("a", RUN1, "src-a", JobExecutionStatus.SUCCESS, T0, T0.plusSeconds(9)),
                job("d", RUN2, "src-d", JobExecutionStatus.SUCCESS, T0.plusSeconds(40), T0.plusSeconds(43)));
        Map<String, ProcessingJobTiming.SourceWindow> windows = ProcessingJobTiming.sourceWindows(
                rows, List.of(run(RUN2, T0.plusSeconds(40), T0.plusSeconds(43)), run(RUN1, T0, T0.plusSeconds(26))));
        assertEquals(Duration.ofSeconds(9), windows.get("src-a").elapsed().orElseThrow());
        assertEquals(Duration.ofSeconds(3), windows.get("src-d").elapsed().orElseThrow());
        assertFalse(windows.containsKey("src-b"));
    }

    @Test
    void failedReprocessDoesNotKeepPreviousSuccessfulTiming() {
        List<ProcessingJobMetadata> rows = List.of(
                job("a-old", RUN1, "src-a", JobExecutionStatus.SUCCESS, T0, T0.plusSeconds(9)),
                job("a-new", RUN2, "src-a", JobExecutionStatus.FAILED, T0.plusSeconds(60), T0.plusSeconds(61)));
        Map<String, ProcessingJobTiming.SourceWindow> windows = ProcessingJobTiming.sourceWindows(
                rows, List.of(
                        new ProcessingRunMetadata(RUN2, "p", "FAILED", true, 1,
                                T0.plusSeconds(60), T0.plusSeconds(61), "failed"),
                        run(RUN1, T0, T0.plusSeconds(26))));
        assertEquals("N/A", label(windows, "src-a"));
    }

    @Test
    void incrementalQueueWriteRetainsPriorSuccessfulWindow() {
        ProcessingJobMetadata prior = job("a", RUN1, "src-a", JobExecutionStatus.SUCCESS, T0, T0.plusSeconds(9));
        assertTrue(ProcessingJobTiming.retainExistingSuccessfulWindow(prior, JobExecutionStatus.QUEUED, false));
        assertTrue(ProcessingJobTiming.retainExistingSuccessfulWindow(prior, JobExecutionStatus.FAILED, false));
        assertFalse(ProcessingJobTiming.retainExistingSuccessfulWindow(prior, JobExecutionStatus.QUEUED, true));
        assertFalse(ProcessingJobTiming.retainExistingSuccessfulWindow(
                job("q", RUN2, "src-a", JobExecutionStatus.QUEUED, T0, null),
                JobExecutionStatus.QUEUED, false));
    }
}
