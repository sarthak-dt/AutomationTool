package com.stellar.platform.desktop.presentation;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessingElapsedTest {

    @Test
    void sourceElapsedUsesEarliestStartAndLatestFinish() {
        Instant t0 = Instant.parse("2026-10-11T10:00:00Z");
        Optional<Duration> elapsed = ProcessingElapsed.sourceElapsed(List.of(
                new ProcessingElapsed.JobClock(t0, t0.plusSeconds(42), Duration.ofSeconds(42)),
                new ProcessingElapsed.JobClock(t0.plusSeconds(10), t0.plusSeconds(78), Duration.ofSeconds(68))
        ));
        assertEquals(Duration.ofSeconds(78), elapsed.orElseThrow());
        assertEquals("01:18", ProcessingElapsed.format(elapsed));
    }

    @Test
    void sharedPassDurationIsNotSummed() {
        Duration pass = Duration.ofSeconds(42);
        Optional<Duration> elapsed = ProcessingElapsed.sourceElapsed(List.of(
                new ProcessingElapsed.JobClock(null, null, pass),
                new ProcessingElapsed.JobClock(null, null, pass)
        ));
        assertEquals(pass, elapsed.orElseThrow());
        assertEquals("00:42", ProcessingElapsed.format(elapsed));
    }

    @Test
    void differingDurationsWithoutBoundsAreUnavailable() {
        Optional<Duration> elapsed = ProcessingElapsed.sourceElapsed(List.of(
                new ProcessingElapsed.JobClock(null, null, Duration.ofSeconds(10)),
                new ProcessingElapsed.JobClock(null, null, Duration.ofSeconds(25))
        ));
        assertTrue(elapsed.isEmpty());
        assertEquals(ProcessingElapsed.UNAVAILABLE, ProcessingElapsed.format(elapsed));
    }

    @Test
    void missingLegacyTimingStaysBlank() {
        Optional<Duration> elapsed = ProcessingElapsed.sourceElapsed(List.of(
                new ProcessingElapsed.JobClock(null, null, null),
                new ProcessingElapsed.JobClock(null, null, Duration.ZERO)
        ));
        assertTrue(elapsed.isEmpty());
        assertEquals(ProcessingElapsed.UNAVAILABLE, ProcessingElapsed.format(elapsed));
    }

    @Test
    void totalPrefersRunWallClockOverAddingJobs() {
        Instant start = Instant.parse("2026-10-11T10:00:00Z");
        Optional<Duration> total = ProcessingElapsed.totalElapsed(
                Duration.ofSeconds(522), start, start.plusSeconds(10));
        assertEquals(Duration.ofSeconds(522), total.orElseThrow());
        assertEquals("08:42", ProcessingElapsed.format(total));
    }

    @Test
    void totalFallsBackToRunInstantsWhenBatchDurationIsMissing() {
        Instant start = Instant.parse("2026-10-11T10:00:00Z");
        Optional<Duration> total = ProcessingElapsed.totalElapsed(
                Duration.ZERO, start, start.plusSeconds(8).plusMillis(200));
        assertEquals("00:08", ProcessingElapsed.format(total));
    }

    @Test
    void totalIsTheCurrentRunOnlyAndNeverAHistoricalSum() {
        Instant start = Instant.parse("2026-10-11T10:00:00Z");
        assertEquals("00:08", ProcessingElapsed.format(ProcessingElapsed.totalElapsed(
                Duration.ofSeconds(8), start, start.plusSeconds(8))),
                "reprocess total is the current run wall clock");
        assertEquals("00:03", ProcessingElapsed.format(ProcessingElapsed.totalElapsed(
                Duration.ofSeconds(3), start.plusSeconds(30), start.plusSeconds(33))),
                "add-files total is the incremental run wall clock");
        assertEquals("00:08", ProcessingElapsed.format(ProcessingElapsed.totalElapsed(
                Duration.ofSeconds(8), start, start.plusSeconds(8 + 26))),
                "batch elapsed wins; historical 26s is not added");
    }

    @Test
    void historicalThirtyMinuteWindowIsRejectedAgainstATenSecondRun() {
        Optional<Duration> shown = ProcessingElapsed.resolveSourceElapsed(
                List.of(new ProcessingElapsed.JobClock(null, null, Duration.ZERO)),
                Optional.of(Duration.ofMinutes(30).plusSeconds(3)),
                Optional.of(Duration.ofSeconds(10)));
        assertTrue(shown.isEmpty());
        assertEquals("N/A", ProcessingElapsed.format(shown));
    }

    @Test
    void noMatchChannelWithoutAWindowDoesNotInvalidateTheSource() {
        Instant t0 = Instant.parse("2026-10-12T10:00:00Z");
        Optional<Duration> elapsed = ProcessingElapsed.sourceElapsed(List.of(
                new ProcessingElapsed.JobClock(t0, t0.plusSeconds(3), Duration.ofSeconds(3)),
                new ProcessingElapsed.JobClock(null, null, Duration.ZERO)));
        assertEquals("00:03", ProcessingElapsed.format(elapsed));
        Optional<Duration> fromDurations = ProcessingElapsed.sourceElapsed(List.of(
                new ProcessingElapsed.JobClock(null, null, Duration.ofSeconds(3)),
                new ProcessingElapsed.JobClock(null, null, Duration.ZERO)));
        assertEquals("00:03", ProcessingElapsed.format(fromDurations));
    }

    @Test
    void currentRunThreeSecondWindowIsKeptAgainstATenSecondRun() {
        Optional<Duration> shown = ProcessingElapsed.resolveSourceElapsed(
                List.of(new ProcessingElapsed.JobClock(null, null, Duration.ZERO)),
                Optional.of(Duration.ofSeconds(3)),
                Optional.of(Duration.ofSeconds(10)));
        assertEquals("00:03", ProcessingElapsed.format(shown));
    }

    @Test
    void formatUsesClockAndHourWhenNeeded() {
        assertEquals("00:08", ProcessingElapsed.format(Duration.ofMillis(8200)));
        assertEquals("01:28", ProcessingElapsed.format(Duration.ofSeconds(88)));
        assertEquals("12:05", ProcessingElapsed.format(Duration.ofSeconds(12 * 60 + 5)));
        assertEquals("01:12:09", ProcessingElapsed.format(Duration.ofSeconds(3600 + 12 * 60 + 9)));
    }
}
