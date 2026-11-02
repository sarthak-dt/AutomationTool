package com.stellar.platform.desktop.presentation;

import com.stellar.platform.api.worker.LocalPlanWorkerEngine;
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
 * Source 1 elapsed after force reprocess: current-run windows only, no 30:03 leak, no N/A
 * when Ch1 has a real clock and Ch2 NO_MATCH does not.
 */
class FirstSourceCurrentRunElapsedTest {

    private static final Instant T0 = Instant.parse("2026-10-12T15:20:32Z");
    private static final String OLD = "run-old";
    private static final String NOW = "run-now";

    @TempDir
    Path temp;

    private static ProcessingJobMetadata job(
            String id, String runId, String source, int channel, JobExecutionStatus status,
            Instant started, Instant finished, String message
    ) {
        return new ProcessingJobMetadata(id, "p", runId, source, channel, "dbc", status,
                1, 3, 1d, status.name(), "isolated-worker", T0, started, finished, null, message);
    }

    private static String label(Map<String, ProcessingJobTiming.SourceWindow> windows, String source) {
        ProcessingJobTiming.SourceWindow window = windows.get(source);
        return ProcessingElapsed.format(window == null ? Optional.empty() : window.elapsed());
    }

    @Test
    void validCh1PlusZeroWindowNoMatchCh2ProducesSourceElapsed() {
        List<ProcessingJobMetadata> rows = List.of(
                job("a1", NOW, "src-1", 1, JobExecutionStatus.SUCCESS_WITH_WARNINGS,
                        T0, T0.plusSeconds(3), ""),
                job("a2", NOW, "src-1", 2, JobExecutionStatus.SUCCESS_WITH_WARNINGS,
                        T0.plusSeconds(3), T0.plusSeconds(3), "NO_MATCH"));
        Map<String, ProcessingJobTiming.SourceWindow> windows =
                ProcessingJobTiming.sourceWindows(rows, NOW, T0);
        assertEquals("00:03", label(windows, "src-1"));
        Optional<Duration> shown = ProcessingElapsed.resolveSourceElapsed(
                List.of(
                        new ProcessingElapsed.JobClock(null, null, Duration.ZERO),
                        new ProcessingElapsed.JobClock(null, null, Duration.ZERO)),
                windows.get("src-1").elapsed(),
                Optional.of(Duration.ofSeconds(10)));
        assertEquals("00:03", ProcessingElapsed.format(shown));
    }

    @Test
    void firstSourceElapsedAfterReprocessUsesCurrentRunOnly() {
        List<ProcessingJobMetadata> rows = List.of(
                job("a1-old", OLD, "src-1", 1, JobExecutionStatus.SUCCESS_WITH_WARNINGS,
                        T0.minusSeconds(1803), T0, ""),
                job("a1", NOW, "src-1", 1, JobExecutionStatus.SUCCESS_WITH_WARNINGS,
                        T0, T0.plusSeconds(3), ""),
                job("a2", NOW, "src-1", 2, JobExecutionStatus.SUCCESS_WITH_WARNINGS,
                        T0.plusSeconds(3), T0.plusSeconds(3), "NO_MATCH"),
                job("b1", NOW, "src-2", 1, JobExecutionStatus.SUCCESS_WITH_WARNINGS,
                        T0.plusSeconds(3), T0.plusSeconds(6), ""),
                job("c1", NOW, "src-3", 1, JobExecutionStatus.SUCCESS_WITH_WARNINGS,
                        T0.plusSeconds(6), T0.plusSeconds(8), ""),
                job("d1", NOW, "src-4", 1, JobExecutionStatus.SUCCESS_WITH_WARNINGS,
                        T0.plusSeconds(8), T0.plusSeconds(11), ""));
        Map<String, ProcessingJobTiming.SourceWindow> windows =
                ProcessingJobTiming.sourceWindows(rows, NOW, T0);
        assertEquals("00:03", label(windows, "src-1"));
        assertEquals("00:03", label(windows, "src-2"));
        assertEquals("00:02", label(windows, "src-3"));
        assertEquals("00:03", label(windows, "src-4"));
        assertFalse(label(windows, "src-1").equals("N/A"));
        Optional<Duration> total = Optional.of(Duration.ofSeconds(10));
        for (String source : List.of("src-1", "src-2", "src-3", "src-4")) {
            Duration elapsed = windows.get(source).elapsed().orElseThrow();
            assertTrue(elapsed.compareTo(Duration.ZERO) > 0);
            assertFalse(ProcessingElapsed.materiallyExceedsRun(elapsed, total), source);
        }
    }

    @Test
    void historicalThirtyMinuteWindowCannotLeakIntoCurrentRun() {
        Instant now = T0.plusSeconds(10);
        Instant[] written = LocalPlanWorkerEngine.executionWindow(
                job("a1-old", OLD, "src-1", 1, JobExecutionStatus.SUCCESS_WITH_WARNINGS,
                        T0.minusSeconds(1803), T0, ""),
                JobExecutionStatus.SUCCESS_WITH_WARNINGS, false, now,
                Duration.ofMinutes(30).plusSeconds(3), NOW, T0);
        assertEquals(now, written[0]);
        assertEquals(now, written[1]);
        Optional<Duration> shown = ProcessingElapsed.resolveSourceElapsed(
                List.of(new ProcessingElapsed.JobClock(null, null, Duration.ZERO)),
                Optional.of(Duration.ofMinutes(30).plusSeconds(3)),
                Optional.of(Duration.ofSeconds(10)));
        assertEquals("N/A", ProcessingElapsed.format(shown));
        assertTrue(ProcessingElapsed.materiallyExceedsRun(Duration.ofMinutes(30).plusSeconds(3),
                Optional.of(Duration.ofSeconds(10))));
    }

    @Test
    void saveReopenPreservesSource1Elapsed() {
        Path db = temp.resolve("source1.db");
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("p", "test", T0, T0, "PROCESSING_RESULT", null));
            store.upsertProcessingRun(new ProcessingRunMetadata(
                    NOW, "p", "COMPLETED", true, 8, T0, T0.plusSeconds(10), "ok"));
            store.upsertProcessingJob(job("a1", NOW, "src-1", 1, JobExecutionStatus.SUCCESS_WITH_WARNINGS,
                    T0, T0.plusSeconds(3), ""));
            store.upsertProcessingJob(job("a2", NOW, "src-1", 2, JobExecutionStatus.SUCCESS_WITH_WARNINGS,
                    T0.plusSeconds(3), T0.plusSeconds(3), "NO_MATCH"));
        }
        try (SqliteProjectMetadataStore reopened = new SqliteProjectMetadataStore(db)) {
            Map<String, ProcessingJobTiming.SourceWindow> windows = ProcessingJobTiming.sourceWindows(
                    reopened.listProcessingJobs("p"), NOW, T0);
            assertEquals("00:03", label(windows, "src-1"));
            assertEquals("00:03", ProcessingElapsed.format(ProcessingElapsed.resolveSourceElapsed(
                    List.of(new ProcessingElapsed.JobClock(null, null, Duration.ZERO)),
                    windows.get("src-1").elapsed(),
                    Optional.of(Duration.ofSeconds(10)))));
        }
    }

    @Test
    void incrementalAddStillPreservesOldSourceTiming() {
        List<ProcessingJobMetadata> rows = List.of(
                job("a1", OLD, "src-1", 1, JobExecutionStatus.SUCCESS_WITH_WARNINGS,
                        T0.minusSeconds(40), T0.minusSeconds(31), ""),
                job("b1", OLD, "src-2", 1, JobExecutionStatus.SUCCESS_WITH_WARNINGS,
                        T0.minusSeconds(31), T0.minusSeconds(14), ""),
                job("e1", NOW, "src-5", 1, JobExecutionStatus.SUCCESS_WITH_WARNINGS,
                        T0, T0.plusSeconds(3), ""));
        Map<String, ProcessingJobTiming.SourceWindow> windows = ProcessingJobTiming.sourceWindows(
                rows, List.of(
                        new ProcessingRunMetadata(NOW, "p", "COMPLETED", false, 1, T0, T0.plusSeconds(3), "ok"),
                        new ProcessingRunMetadata(OLD, "p", "COMPLETED", false, 2,
                                T0.minusSeconds(40), T0.minusSeconds(14), "ok")));
        assertEquals("00:09", label(windows, "src-1"));
        assertEquals("00:17", label(windows, "src-2"));
        assertEquals("00:03", label(windows, "src-5"));
    }
}
