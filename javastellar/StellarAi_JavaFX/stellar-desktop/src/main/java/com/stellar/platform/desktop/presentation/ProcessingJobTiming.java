package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.storage.metadata.ProcessingJobMetadata;
import com.stellar.platform.storage.metadata.ProcessingRunMetadata;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Per-source wall clock rebuilt from the durable processing ledger ({@code processing_jobs}).
 * <p>
 * Per-job elapsed is not an artifact field and the isolated worker returns only counts, so a
 * reopened project (and a worker run) has no in-memory durations. The ledger does keep
 * timestamps, and this class reads them without any new column.
 * <ol>
 *   <li><b>Recorded window.</b> Rows written by the current worker keep the start written at
 *       {@code RUNNING} and a finish written when the job ended. Source elapsed is latest
 *       finish minus earliest start of those valid windows. A NO_MATCH channel without a
 *       window is ignored and does not invalidate the source.</li>
 *   <li><b>Derived window (older projects).</b> Earlier builds overwrote the start with the
 *       finish, so those rows have {@code started == finished}. Sources run one after another
 *       in a single pass, so a source began when the previous source finished, and the first
 *       source began when the run began. The derived span is then
 *       {@code finish(source) - finish(previous source)}. It needs strictly ordered finish
 *       times and a run start; otherwise the source stays unavailable. Its start is the real
 *       run start or the real previous finish, so it also includes artifact commit time.</li>
 *   <li>Anything else is unavailable. No duration is guessed.</li>
 * </ol>
 */
public final class ProcessingJobTiming {

    private ProcessingJobTiming() {
    }

    /**
     * @param started  window start
     * @param finished window end
     * @param derived  true when the start was inferred from the previous source finish or run start
     */
    public record SourceWindow(Instant started, Instant finished, boolean derived) {

        /** Positive span only. A zero or negative span is not a measurement. */
        public Optional<Duration> elapsed() {
            if (started == null || finished == null || !finished.isAfter(started)) {
                return Optional.empty();
            }
            return Optional.of(Duration.between(started, finished));
        }
    }

    /**
     * Incremental plan-wide writes must not erase a prior successful window.
     * Force reprocess supersedes every current job, including a failed reprocess.
     */
    public static boolean retainExistingSuccessfulWindow(
            ProcessingJobMetadata prior,
            JobExecutionStatus incoming,
            boolean forceReprocess
    ) {
        if (forceReprocess || prior == null || incoming == null
                || prior.status() == null || !prior.status().isSuccessful()) {
            return false;
        }
        return incoming == JobExecutionStatus.QUEUED
                || incoming == JobExecutionStatus.FAILED
                || incoming == JobExecutionStatus.CANCELLED
                || incoming == JobExecutionStatus.BLOCKED;
    }

    /**
     * Reconstructs per-source windows across project runs. The newest run that touched a
     * source wins; sources absent from that run keep their earlier valid window. A source
     * present in a newer run without a valid window stays unavailable — old SUCCESS is
     * not mixed into a failed or untimed reprocess.
     *
     * @param rows             persisted job rows for the project
     * @param runsNewestFirst  processing runs, newest first ({@code started_at DESC})
     */
    public static Map<String, SourceWindow> sourceWindows(
            List<ProcessingJobMetadata> rows,
            List<ProcessingRunMetadata> runsNewestFirst
    ) {
        Map<String, SourceWindow> result = new LinkedHashMap<>();
        Set<String> decided = new HashSet<>();
        if (runsNewestFirst == null) {
            return result;
        }
        for (ProcessingRunMetadata run : runsNewestFirst) {
            if (run == null || run.runId() == null || run.runId().isBlank()) {
                continue;
            }
            Set<String> inThisRun = sourcesInRun(rows, run.runId());
            if (inThisRun.isEmpty()) {
                continue;
            }
            Map<String, SourceWindow> windows = sourceWindows(rows, run.runId(), run.startedAt());
            for (String source : inThisRun) {
                if (!decided.add(source)) {
                    continue;
                }
                SourceWindow window = windows.get(source);
                if (window != null) {
                    result.put(source, window);
                }
            }
        }
        return result;
    }

    /**
     * @param rows       persisted job rows for the project
     * @param runId      run the summary belongs to; blank accepts every row
     * @param runStarted persisted run start used for the first source of a derived chain
     * @return source id to window, only for sources that have a measurable window
     */
    public static Map<String, SourceWindow> sourceWindows(
            List<ProcessingJobMetadata> rows,
            String runId,
            Instant runStarted
    ) {
        Map<String, List<ProcessingJobMetadata>> bySource = new LinkedHashMap<>();
        if (rows != null) {
            for (ProcessingJobMetadata row : rows) {
                if (row == null || row.sourceId() == null || row.sourceId().isBlank()) {
                    continue;
                }
                if (runId != null && !runId.isBlank() && !runId.equals(row.runId())) {
                    continue;
                }
                bySource.computeIfAbsent(row.sourceId(), key -> new ArrayList<>()).add(row);
            }
        }
        Map<String, SourceWindow> result = new HashMap<>();
        Map<String, Instant> finishBySource = new LinkedHashMap<>();
        for (Map.Entry<String, List<ProcessingJobMetadata>> entry : bySource.entrySet()) {
            Instant finish = latestFinish(entry.getValue());
            if (finish != null) {
                finishBySource.put(entry.getKey(), finish);
            }
            SourceWindow recorded = recordedWindow(entry.getValue());
            if (recorded != null) {
                result.put(entry.getKey(), recorded);
            }
        }
        deriveFromCompletionOrder(finishBySource, runStarted, result);
        return result;
    }

    private static Set<String> sourcesInRun(List<ProcessingJobMetadata> rows, String runId) {
        Set<String> sources = new HashSet<>();
        if (rows == null || runId == null || runId.isBlank()) {
            return sources;
        }
        for (ProcessingJobMetadata row : rows) {
            if (row != null && runId.equals(row.runId())
                    && row.sourceId() != null && !row.sourceId().isBlank()) {
                sources.add(row.sourceId());
            }
        }
        return sources;
    }

    /**
     * Only successful rows with a real start and finish. A leftover QUEUED, RUNNING,
     * NO_MATCH, or zero-duration {@code started==finished} row must not hide or replace a
     * later SUCCESS window. Multiple channels of one source in the same run share one pass
     * and are combined from the valid clocks only.
     */
    private static SourceWindow recordedWindow(List<ProcessingJobMetadata> jobs) {
        Instant earliest = null;
        Instant latest = null;
        int real = 0;
        for (ProcessingJobMetadata job : jobs) {
            if (job.status() == null || !job.status().isSuccessful() || job.startedAt() == null
                    || job.finishedAt() == null || !job.finishedAt().isAfter(job.startedAt())) {
                continue;
            }
            real++;
            if (earliest == null || job.startedAt().isBefore(earliest)) {
                earliest = job.startedAt();
            }
            if (latest == null || job.finishedAt().isAfter(latest)) {
                latest = job.finishedAt();
            }
        }
        return real == 0 ? null : new SourceWindow(earliest, latest, false);
    }

    private static Instant latestFinish(List<ProcessingJobMetadata> jobs) {
        Instant latest = null;
        for (ProcessingJobMetadata job : jobs) {
            if (job.status() == null || !job.status().isSuccessful() || job.finishedAt() == null) {
                continue;
            }
            if (latest == null || job.finishedAt().isAfter(latest)) {
                latest = job.finishedAt();
            }
        }
        return latest;
    }

    private static void deriveFromCompletionOrder(
            Map<String, Instant> finishBySource,
            Instant runStarted,
            Map<String, SourceWindow> result
    ) {
        if (runStarted == null || finishBySource.isEmpty()) {
            return;
        }
        List<Map.Entry<String, Instant>> ordered = new ArrayList<>(finishBySource.entrySet());
        ordered.sort(Map.Entry.comparingByValue(Comparator.naturalOrder()));
        Instant previous = runStarted;
        for (int i = 0; i < ordered.size(); i++) {
            Map.Entry<String, Instant> entry = ordered.get(i);
            Instant finish = entry.getValue();
            boolean tiedWithNeighbour = (i > 0 && ordered.get(i - 1).getValue().equals(finish))
                    || (i + 1 < ordered.size() && ordered.get(i + 1).getValue().equals(finish));
            if (!finish.isAfter(previous)) {
                continue;
            }
            if (!tiedWithNeighbour && !result.containsKey(entry.getKey())) {
                result.put(entry.getKey(), new SourceWindow(previous, finish, true));
            }
            previous = finish;
        }
    }
}
