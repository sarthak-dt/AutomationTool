package com.stellar.platform.desktop.presentation;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Wall-clock processing time for the Processing Complete screen.
 * <p>
 * A physical measurement's channel jobs often share one routed pass
 * ({@code ProjectPlanExecutor} writes the same pass {@link Duration} onto each channel).
 * Source elapsed is therefore not the sum of those durations.
 * <p>
 * Definition:
 * <ul>
 *   <li>When any current-run job has a start instant and a finish instant, source elapsed is
 *       latest finish minus earliest start of those valid windows. A NO_MATCH / zero-work
 *       channel without a window is ignored and does not invalidate the source.</li>
 *   <li>When those instants are absent and every measured positive job duration is the same
 *       value, that value is the shared-pass wall clock. Zero durations are ignored.</li>
 *   <li>Differing positive durations without start and finish instants are unavailable.
 *       Summing them could double-count a shared pass, and a maximum could hide
 *       sequential work. The screen shows {@link #UNAVAILABLE} instead of a guess.</li>
 *   <li>Total time prefers the batch duration, which the executor records as
 *       run start to run completion. A persisted run start and finish is used only
 *       when the batch duration itself is missing.</li>
 * </ul>
 */
public final class ProcessingElapsed {

    public static final String UNAVAILABLE = "N/A";
    public static final String UNAVAILABLE_TOOLTIP =
            "Per-source elapsed was not available for this processing run.";

    /**
     * @param started  job execution start, or null when the result does not carry one
     * @param finished job execution finish, or null when the result does not carry one
     * @param elapsed  measured job duration, or null when this record was not timed
     */
    public record JobClock(Instant started, Instant finished, Duration elapsed) {
    }

    private ProcessingElapsed() {
    }

    public static Optional<Duration> sourceElapsed(List<JobClock> jobs) {
        if (jobs == null || jobs.isEmpty()) {
            return Optional.empty();
        }
        Instant earliest = null;
        Instant latest = null;
        int bounded = 0;
        for (JobClock job : jobs) {
            if (job == null || job.started() == null || job.finished() == null
                    || !job.finished().isAfter(job.started())) {
                continue;
            }
            bounded++;
            if (earliest == null || job.started().isBefore(earliest)) {
                earliest = job.started();
            }
            if (latest == null || job.finished().isAfter(latest)) {
                latest = job.finished();
            }
        }
        if (bounded > 0 && earliest != null && latest != null) {
            return Optional.of(Duration.between(earliest, latest));
        }
        Duration shared = null;
        int measured = 0;
        for (JobClock job : jobs) {
            Duration elapsed = job == null ? null : job.elapsed();
            if (elapsed == null || elapsed.isNegative() || elapsed.isZero()) {
                continue;
            }
            measured++;
            if (shared == null) {
                shared = elapsed;
            } else if (!shared.equals(elapsed)) {
                return Optional.empty();
            }
        }
        if (measured == 0 || shared == null) {
            return Optional.empty();
        }
        return Optional.of(shared);
    }

    /**
     * Source elapsed for display. In-memory job timing wins because it is the executor's own
     * measurement. When the results carry none (reopened project, isolated worker run) the
     * window rebuilt from the processing ledger is used. Neither available gives empty.
     */
    public static Optional<Duration> resolveSourceElapsed(List<JobClock> jobs, Optional<Duration> persistedWindow) {
        return resolveSourceElapsed(jobs, persistedWindow, Optional.empty());
    }

    /**
     * Source elapsed for the Processing Complete run. A window that materially exceeds the
     * run wall clock is treated as a broken correlation (historical bleed) and is not shown.
     */
    public static Optional<Duration> resolveSourceElapsed(
            List<JobClock> jobs,
            Optional<Duration> persistedWindow,
            Optional<Duration> runElapsed
    ) {
        Optional<Duration> measured = sourceElapsed(jobs);
        Optional<Duration> resolved;
        if (measured.isPresent()) {
            resolved = measured;
        } else if (persistedWindow != null && persistedWindow.isPresent()
                && !persistedWindow.get().isNegative() && !persistedWindow.get().isZero()) {
            resolved = persistedWindow;
        } else {
            resolved = Optional.empty();
        }
        if (resolved.isPresent() && materiallyExceedsRun(resolved.get(), runElapsed)) {
            return Optional.empty();
        }
        return resolved;
    }

    /**
     * Sequential sources cannot take longer than the run plus a small scheduler slack
     * ({@code 2s + 5%} of the run).
     */
    public static boolean materiallyExceedsRun(Duration sourceElapsed, Optional<Duration> runElapsed) {
        if (sourceElapsed == null || runElapsed == null || runElapsed.isEmpty()) {
            return false;
        }
        Duration run = runElapsed.get();
        if (run.isNegative() || run.isZero() || sourceElapsed.isNegative()) {
            return false;
        }
        Duration slack = Duration.ofSeconds(2).plusMillis(Math.max(0L, run.toMillis() / 20L));
        return sourceElapsed.compareTo(run.plus(slack)) > 0;
    }

    /**
     * Run wall clock. {@code batchElapsed} is the executor's run start to run completion.
     * Run instants are the fallback for a reopened project whose batch duration was not kept.
     */
    public static Optional<Duration> totalElapsed(Duration batchElapsed, Instant runStarted, Instant runFinished) {
        if (batchElapsed != null && !batchElapsed.isNegative() && !batchElapsed.isZero()) {
            return Optional.of(batchElapsed);
        }
        if (runStarted != null && runFinished != null && runFinished.isAfter(runStarted)) {
            return Optional.of(Duration.between(runStarted, runFinished));
        }
        return Optional.empty();
    }

    public static String format(Duration duration) {
        if (duration == null || duration.isNegative()) {
            return UNAVAILABLE;
        }
        long seconds = duration.toSeconds();
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long remain = seconds % 60L;
        if (hours > 0L) {
            return String.format("%02d:%02d:%02d", hours, minutes, remain);
        }
        return String.format("%02d:%02d", minutes, remain);
    }

    public static String format(Optional<Duration> duration) {
        return duration == null ? UNAVAILABLE : duration.map(ProcessingElapsed::format).orElse(UNAVAILABLE);
    }
}
