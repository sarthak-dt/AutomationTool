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
 * Processing Complete elapsed is the displayed run only. A 30-minute historical window
 * must never appear on a 10-second current run.
 */
class ProcessingCompleteRunScopedElapsedTest {

    private static final Instant T0 = Instant.parse("2026-10-12T10:00:00Z");
    private static final String OLD = "run-old";
    private static final String NOW = "run-now";

    @TempDir
    Path temp;

    private static ProcessingJobMetadata job(
            String id, String runId, String source, Instant started, Instant finished
    ) {
        return new ProcessingJobMetadata(id, "p", runId, source, 1, "dbc", JobExecutionStatus.SUCCESS,
                1, 3, 1d, "SUCCESS", "isolated-worker", T0, started, finished, null, "");
    }

    private static ProcessingRunMetadata run(String runId, Instant started, Instant finished) {
        return new ProcessingRunMetadata(runId, "p", "COMPLETED", false, 4, started, finished, "ok");
    }

    private static String label(Map<String, ProcessingJobTiming.SourceWindow> windows, String source) {
        ProcessingJobTiming.SourceWindow window = windows.get(source);
        return ProcessingElapsed.format(window == null ? Optional.empty() : window.elapsed());
    }

    @Test
    void currentRunIgnoresThirtyMinuteHistoricalWindow() {
        Instant oldStart = T0.minusSeconds(1803);
        Instant oldEnd = T0;
        Instant nowStart = T0.plusSeconds(3600);
        List<ProcessingJobMetadata> rows = List.of(
                job("a-old", OLD, "src-a", oldStart, oldEnd),
                job("a-now", NOW, "src-a", nowStart, nowStart.plusSeconds(3)),
                job("b-now", NOW, "src-b", nowStart.plusSeconds(3), nowStart.plusSeconds(6)),
                job("c-now", NOW, "src-c", nowStart.plusSeconds(6), nowStart.plusSeconds(8)),
                job("d-now", NOW, "src-d", nowStart.plusSeconds(8), nowStart.plusSeconds(10)));
        Map<String, ProcessingJobTiming.SourceWindow> current = ProcessingJobTiming.sourceWindows(
                rows, NOW, nowStart);
        assertEquals("00:03", label(current, "src-a"));
        assertEquals("00:03", label(current, "src-b"));
        assertEquals("00:02", label(current, "src-c"));
        assertEquals("00:02", label(current, "src-d"));
        Optional<Duration> total = ProcessingElapsed.totalElapsed(
                Duration.ofSeconds(10), nowStart, nowStart.plusSeconds(10));
        assertEquals("00:10", ProcessingElapsed.format(total));
        assertEquals("00:03", ProcessingElapsed.format(ProcessingElapsed.resolveSourceElapsed(
                List.of(new ProcessingElapsed.JobClock(null, null, Duration.ZERO)),
                current.get("src-a").elapsed(), total)));
        assertTrue(ProcessingElapsed.materiallyExceedsRun(Duration.ofMinutes(30).plusSeconds(3), total));
        assertFalse(ProcessingElapsed.materiallyExceedsRun(Duration.ofSeconds(3), total));
    }

    @Test
    void sameSourceAcrossRunIdsUsesOnlyTheDisplayedRun() {
        List<ProcessingJobMetadata> rows = List.of(
                job("a-old", OLD, "src-a", T0, T0.plusSeconds(1803)),
                job("a-now", NOW, "src-a", T0.plusSeconds(4000), T0.plusSeconds(4003)));
        assertEquals("30:03", ProcessingElapsed.format(
                ProcessingJobTiming.sourceWindows(rows, OLD, T0).get("src-a").elapsed()));
        assertEquals("00:03", ProcessingElapsed.format(
                ProcessingJobTiming.sourceWindows(rows, NOW, T0.plusSeconds(4000)).get("src-a").elapsed()));
    }

    @Test
    void reprocessAndIncrementalSelectCurrentRunOnly() {
        Instant reprocessStart = T0.plusSeconds(5000);
        List<ProcessingJobMetadata> rows = List.of(
                job("a1", OLD, "src-a", T0, T0.plusSeconds(1803)),
                job("a2", NOW, "src-a", reprocessStart, reprocessStart.plusSeconds(3)),
                job("b2", NOW, "src-b", reprocessStart.plusSeconds(3), reprocessStart.plusSeconds(6)));
        Map<String, ProcessingJobTiming.SourceWindow> reprocess =
                ProcessingJobTiming.sourceWindows(rows, NOW, reprocessStart);
        assertEquals("00:03", label(reprocess, "src-a"));
        assertEquals("00:03", label(reprocess, "src-b"));
        assertFalse(reprocess.containsKey("src-c"));

        Instant incStart = T0.plusSeconds(8000);
        List<ProcessingJobMetadata> incremental = List.of(
                job("a2", NOW, "src-a", reprocessStart, reprocessStart.plusSeconds(3)),
                job("e", "run-inc", "src-e", incStart, incStart.plusSeconds(4)));
        Map<String, ProcessingJobTiming.SourceWindow> inc =
                ProcessingJobTiming.sourceWindows(incremental, "run-inc", incStart);
        assertEquals("00:04", label(inc, "src-e"));
        assertFalse(inc.containsKey("src-a"), "incremental run must not inherit src-a 00:03 from the prior run");
    }

    @Test
    void reopenPreservesCurrentRunScopedTiming() {
        Path db = temp.resolve("run-scope.db");
        Instant start = T0.plusSeconds(9000);
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("p", "test", T0, T0, "PROCESSING_RESULT", null));
            store.upsertProcessingRun(run(OLD, T0, T0.plusSeconds(1803)));
            store.upsertProcessingJob(job("a-old", OLD, "src-a", T0, T0.plusSeconds(1803)));
            store.upsertProcessingRun(run(NOW, start, start.plusSeconds(10)));
            store.upsertProcessingJob(job("a-now", NOW, "src-a", start, start.plusSeconds(3)));
            store.upsertProcessingJob(job("b-now", NOW, "src-b", start.plusSeconds(3), start.plusSeconds(10)));
        }
        try (SqliteProjectMetadataStore reopened = new SqliteProjectMetadataStore(db)) {
            Map<String, ProcessingJobTiming.SourceWindow> windows = ProcessingJobTiming.sourceWindows(
                    reopened.listProcessingJobs("p"), NOW, start);
            assertEquals("00:03", label(windows, "src-a"));
            assertEquals("00:07", label(windows, "src-b"));
            assertEquals("00:10", ProcessingElapsed.format(ProcessingElapsed.totalElapsed(
                    Duration.ofSeconds(10), start, start.plusSeconds(10))));
        }
    }

    @Test
    void firstSourceKeepsCh1WindowWhenCh2HasNoTiming() {
        Instant start = T0.plusSeconds(200);
        List<ProcessingJobMetadata> rows = List.of(
                new ProcessingJobMetadata("a1", "p", NOW, "src-a", 1, "dbc", JobExecutionStatus.SUCCESS,
                        1, 3, 1d, "SUCCESS", "isolated-worker", start, start, start.plusSeconds(3), null, ""),
                new ProcessingJobMetadata("a2", "p", NOW, "src-a", 2, "", JobExecutionStatus.SUCCESS,
                        1, 3, 1d, "SUCCESS", "isolated-worker", start, start.plusSeconds(3), start.plusSeconds(3),
                        null, "NO_MATCH"),
                job("b", NOW, "src-b", start.plusSeconds(3), start.plusSeconds(6)),
                job("c", NOW, "src-c", start.plusSeconds(6), start.plusSeconds(8)),
                job("d", NOW, "src-d", start.plusSeconds(8), start.plusSeconds(11)));
        Map<String, ProcessingJobTiming.SourceWindow> windows =
                ProcessingJobTiming.sourceWindows(rows, NOW, start);
        assertEquals("00:03", label(windows, "src-a"));
        assertEquals("00:03", label(windows, "src-b"));
        assertEquals("00:02", label(windows, "src-c"));
        assertEquals("00:03", label(windows, "src-d"));
        Optional<Duration> total = Optional.of(Duration.ofSeconds(13));
        assertEquals("00:03", ProcessingElapsed.format(ProcessingElapsed.resolveSourceElapsed(
                List.of(
                        new ProcessingElapsed.JobClock(null, null, Duration.ofSeconds(3)),
                        new ProcessingElapsed.JobClock(null, null, Duration.ZERO)),
                windows.get("src-a").elapsed(), total)));
        assertTrue(ProcessingElapsed.materiallyExceedsRun(Duration.ofMinutes(30).plusSeconds(3), total));
    }

    @Test
    void twoChannelsOfOneSourceUseOnlyCurrentRunJobs() {
        Instant start = T0.plusSeconds(200);
        List<ProcessingJobMetadata> rows = List.of(
                new ProcessingJobMetadata("a1-old", "p", OLD, "src-a", 1, "dbc", JobExecutionStatus.SUCCESS,
                        1, 3, 1d, "SUCCESS", "isolated-worker", T0, T0, T0.plusSeconds(1803), null, ""),
                new ProcessingJobMetadata("a1", "p", NOW, "src-a", 1, "dbc", JobExecutionStatus.SUCCESS,
                        1, 3, 1d, "SUCCESS", "isolated-worker", start, start, start.plusSeconds(2), null, ""),
                new ProcessingJobMetadata("a2", "p", NOW, "src-a", 2, "dbc", JobExecutionStatus.SUCCESS,
                        1, 3, 1d, "SUCCESS", "isolated-worker", start, start.plusSeconds(1), start.plusSeconds(3),
                        null, ""));
        Map<String, ProcessingJobTiming.SourceWindow> windows =
                ProcessingJobTiming.sourceWindows(rows, NOW, start);
        assertEquals(Duration.ofSeconds(3), windows.get("src-a").elapsed().orElseThrow());
    }
}
