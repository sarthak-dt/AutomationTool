package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.storage.metadata.ProcessingJobMetadata;
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

/** Per-source Elapsed after a project is reopened, and the behaviour for older ledgers. */
class ProcessingJobTimingTest {

    private static final Instant T0 = Instant.parse("2026-10-11T10:00:00Z");
    private static final String RUN = "run-1";

    @TempDir
    Path temp;

    private static ProcessingJobMetadata job(
            String id, String source, int channel, JobExecutionStatus status, Instant started, Instant finished
    ) {
        return new ProcessingJobMetadata(id, "p", RUN, source, channel, "dbc", status,
                1, 3, 1d, status.name(), "isolated-worker", T0, started, finished, null, "");
    }

    @Test
    void recordedWindowGivesLatestFinishMinusEarliestStartPerSource() {
        // Two channels of one source share a pass: counted once, not summed.
        List<ProcessingJobMetadata> rows = List.of(
                job("a1", "src-a", 1, JobExecutionStatus.SUCCESS, T0, T0.plusSeconds(10)),
                job("a2", "src-a", 2, JobExecutionStatus.SUCCESS, T0, T0.plusSeconds(11)),
                job("b1", "src-b", 1, JobExecutionStatus.SUCCESS, T0.plusSeconds(11), T0.plusSeconds(26)));

        Map<String, ProcessingJobTiming.SourceWindow> windows = ProcessingJobTiming.sourceWindows(rows, RUN, T0);

        assertEquals(Duration.ofSeconds(11), windows.get("src-a").elapsed().orElseThrow());
        assertEquals(Duration.ofSeconds(15), windows.get("src-b").elapsed().orElseThrow());
        assertFalse(windows.get("src-a").derived());
        assertEquals("00:11", ProcessingElapsed.format(windows.get("src-a").elapsed()));
    }

    @Test
    void windowSurvivesProjectReopenThroughSqlite() {
        Path db = temp.resolve("reopen.db");
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("p", "test", Instant.now(), Instant.now(), "PROCESSING_RESULT", null));
            store.upsertProcessingJob(job("a1", "src-a", 1, JobExecutionStatus.SUCCESS, T0, T0.plusSeconds(9)));
            store.upsertProcessingJob(job("b1", "src-b", 1, JobExecutionStatus.SUCCESS_WITH_WARNINGS,
                    T0.plusSeconds(9), T0.plusSeconds(26)));
        }
        try (SqliteProjectMetadataStore reopened = new SqliteProjectMetadataStore(db)) {
            Map<String, ProcessingJobTiming.SourceWindow> windows =
                    ProcessingJobTiming.sourceWindows(reopened.listProcessingJobs("p"), RUN, T0);
            // Reopened results carry Duration.ZERO, so the ledger window is what the screen shows.
            Optional<Duration> a = ProcessingElapsed.resolveSourceElapsed(
                    List.of(new ProcessingElapsed.JobClock(null, null, Duration.ZERO)),
                    windows.get("src-a").elapsed());
            Optional<Duration> b = ProcessingElapsed.resolveSourceElapsed(
                    List.of(new ProcessingElapsed.JobClock(null, null, Duration.ZERO)),
                    windows.get("src-b").elapsed());
            assertEquals("00:09", ProcessingElapsed.format(a));
            assertEquals("00:17", ProcessingElapsed.format(b));
        }
    }

    @Test
    void olderLedgerWithOverwrittenStartsIsDerivedFromCompletionOrder() {
        // Older builds wrote started == finished. Sources ran one after another.
        List<ProcessingJobMetadata> rows = List.of(
                job("a1", "src-a", 1, JobExecutionStatus.SUCCESS, T0.plusSeconds(8), T0.plusSeconds(8)),
                job("a2", "src-a", 2, JobExecutionStatus.SUCCESS, T0.plusSeconds(8), T0.plusSeconds(8)),
                job("b1", "src-b", 1, JobExecutionStatus.SUCCESS, T0.plusSeconds(26), T0.plusSeconds(26)));

        Map<String, ProcessingJobTiming.SourceWindow> windows = ProcessingJobTiming.sourceWindows(rows, RUN, T0);

        assertEquals(Duration.ofSeconds(8), windows.get("src-a").elapsed().orElseThrow());
        assertEquals(Duration.ofSeconds(18), windows.get("src-b").elapsed().orElseThrow());
        assertTrue(windows.get("src-a").derived());
        assertTrue(windows.get("src-b").derived());
    }

    @Test
    void missingTimingStaysUnavailableAndIsNeverShownAsZero() {
        // No run start: nothing to anchor the first source to.
        List<ProcessingJobMetadata> degenerate = List.of(
                job("a1", "src-a", 1, JobExecutionStatus.SUCCESS, T0, T0));
        Map<String, ProcessingJobTiming.SourceWindow> none = ProcessingJobTiming.sourceWindows(degenerate, RUN, null);
        assertTrue(none.isEmpty());

        // Same finish second for two sources: order is ambiguous, so neither is derived.
        List<ProcessingJobMetadata> tied = List.of(
                job("a1", "src-a", 1, JobExecutionStatus.SUCCESS, T0.plusSeconds(5), T0.plusSeconds(5)),
                job("b1", "src-b", 1, JobExecutionStatus.SUCCESS, T0.plusSeconds(5), T0.plusSeconds(5)));
        assertTrue(ProcessingJobTiming.sourceWindows(tied, RUN, T0).isEmpty());

        // A job that never finished leaves its source unmeasured.
        List<ProcessingJobMetadata> unfinished = List.of(
                job("a1", "src-a", 1, JobExecutionStatus.RUNNING, T0, null));
        assertTrue(ProcessingJobTiming.sourceWindows(unfinished, RUN, T0).isEmpty());

        // Rows of another run are not mixed in.
        List<ProcessingJobMetadata> otherRun = List.of(
                job("a1", "src-a", 1, JobExecutionStatus.SUCCESS, T0, T0.plusSeconds(4)));
        assertTrue(ProcessingJobTiming.sourceWindows(otherRun, "run-2", T0).isEmpty());

        assertEquals("N/A", ProcessingElapsed.format(ProcessingElapsed.resolveSourceElapsed(
                List.of(new ProcessingElapsed.JobClock(null, null, Duration.ZERO)), Optional.empty())));
    }

    @Test
    void leftoverQueuedRowDoesNotHideARecordedSuccessWindow() {
        List<ProcessingJobMetadata> rows = List.of(
                job("queued", "src-a", 1, JobExecutionStatus.QUEUED, T0, null),
                job("done", "src-a", 1, JobExecutionStatus.SUCCESS, T0.plusSeconds(2), T0.plusSeconds(9)));
        Map<String, ProcessingJobTiming.SourceWindow> windows = ProcessingJobTiming.sourceWindows(rows, RUN, T0);
        assertEquals(Duration.ofSeconds(7), windows.get("src-a").elapsed().orElseThrow());
        assertFalse(windows.get("src-a").derived());
    }

    @Test
    void inMemoryMeasurementStillWinsWhenPresent() {
        Optional<Duration> resolved = ProcessingElapsed.resolveSourceElapsed(
                List.of(new ProcessingElapsed.JobClock(null, null, Duration.ofSeconds(42))),
                Optional.of(Duration.ofSeconds(99)));
        assertEquals(Duration.ofSeconds(42), resolved.orElseThrow());
    }

    @Test
    void zeroLengthBoundedClockIsNotReportedAsAMeasurement() {
        Optional<Duration> resolved = ProcessingElapsed.sourceElapsed(
                List.of(new ProcessingElapsed.JobClock(T0, T0, null)));
        assertTrue(resolved.isEmpty());
    }
}
