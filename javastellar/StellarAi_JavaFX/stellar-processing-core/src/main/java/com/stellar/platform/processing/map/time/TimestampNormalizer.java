package com.stellar.platform.processing.map.time;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.OptionalInt;

/**
 * Turns one extracted clock into a recording start.
 * An empty user offset never promotes local civil time, a filename, or a relative sample.
 */
public final class TimestampNormalizer {

    /** Inclusive bound for a real civil offset, in minutes. */
    public static final int MAX_OFFSET_MINUTES = 14 * 60;
    private static final long NANOS_PER_MINUTE = 60_000_000_000L;

    private TimestampNormalizer() {
    }

    public static RecordingClock normalize(TimestampObservation observation) {
        return normalize(observation, OptionalInt.empty());
    }

    /**
     * {@code userOffsetMinutes} is reserved for an explicit operator choice.
     * Callers pass {@link OptionalInt#empty()} until that choice exists.
     * GPS coordinates, the filename, and the host timezone are not substitutes.
     */
    public static RecordingClock normalize(TimestampObservation observation, OptionalInt userOffsetMinutes) {
        TimestampObservation row = observation == null
                ? TimestampObservation.none("", "no timestamp observation")
                : observation;
        OptionalInt userOffset = userOffsetMinutes == null ? OptionalInt.empty() : userOffsetMinutes;
        return switch (row.rawKind()) {
            case ELAPSED -> RecordingClock.unordered(
                    row.measurementId(), row.source(), ClockKind.RELATIVE_ONLY, row.unit(),
                    reason(row, "elapsed measurement time is not a recording start"));
            case ISO_ZONED -> zonedSample(row);
            case EPOCH_NANOS -> epoch(row, userOffset);
            case CIVIL_LOCAL, ISO_UNZONED -> civil(row, userOffset);
            case UNKNOWN -> RecordingClock.unordered(
                    row.measurementId(), row.source(), ClockKind.MISSING, row.unit(),
                    reason(row, "recording time is missing"));
        };
    }

    private static RecordingClock zonedSample(TimestampObservation row) {
        if (!row.zoneExplicit() || row.rawValueNanos() <= 0L) {
            return RecordingClock.unordered(
                    row.measurementId(), row.source(), ClockKind.MISSING, row.unit(),
                    reason(row, "zoned timestamp is missing"));
        }
        return comparable(row, ClockKind.ABSOLUTE_SAMPLE, null, row.rawValueNanos(),
                reason(row, "ISO timestamp includes a timezone"));
    }

    private static RecordingClock epoch(TimestampObservation row, OptionalInt userOffset) {
        if (row.rawValueNanos() <= 0L) {
            return RecordingClock.unordered(
                    row.measurementId(), row.source(), ClockKind.MISSING, TimeUnitKind.NANOSECONDS,
                    reason(row, "recording start is zero"));
        }
        if (!row.localTime() && row.zoneExplicit()) {
            return comparable(row, ClockKind.UTC, row.offsetMinutes(), row.rawValueNanos(),
                    reason(row, "recording start is UTC"));
        }
        if (row.localTime() && row.zoneExplicit()) {
            if (!validOffset(row.offsetMinutes())) {
                return RecordingClock.unordered(
                        row.measurementId(), row.source(), ClockKind.MISSING, TimeUnitKind.NANOSECONDS,
                        "timezone offset is outside ±14 hours");
            }
            Long utc = subtractOffset(row.rawValueNanos(), row.offsetMinutes());
            if (utc == null) {
                return RecordingClock.unordered(
                        row.measurementId(), row.source(), ClockKind.MISSING, TimeUnitKind.NANOSECONDS,
                        "recording start overflowed");
            }
            return comparable(row, ClockKind.LOCAL_WITH_OFFSET, row.offsetMinutes(), utc,
                    reason(row, "local recording start converted with its offset"));
        }
        return applyUserOffset(row, userOffset, row.rawValueNanos(), null);
    }

    private static RecordingClock civil(TimestampObservation row, OptionalInt userOffset) {
        if (row.localTime() && row.zoneExplicit() && row.civilLocal() != null) {
            if (!validOffset(row.offsetMinutes())) {
                return RecordingClock.unordered(
                        row.measurementId(), row.source(), ClockKind.MISSING, TimeUnitKind.NONE,
                        "timezone offset is outside ±14 hours");
            }
            Long utc = civilToUtc(row.civilLocal(), row.offsetMinutes());
            if (utc == null) {
                return RecordingClock.unordered(
                        row.measurementId(), row.source(), ClockKind.MISSING, TimeUnitKind.NONE,
                        "civil recording time could not be converted");
            }
            return comparable(row, ClockKind.LOCAL_WITH_OFFSET, row.offsetMinutes(), utc,
                    reason(row, "civil time converted with its offset"));
        }
        return applyUserOffset(row, userOffset, 0L, row.civilLocal());
    }

    /**
     * Applies only an offset the caller supplied. A missing offset leaves the clock unordered.
     */
    private static RecordingClock applyUserOffset(
            TimestampObservation row,
            OptionalInt userOffset,
            long localEpochNanos,
            LocalDateTime civil
    ) {
        if (userOffset.isEmpty()) {
            return RecordingClock.unordered(
                    row.measurementId(), row.source(), ClockKind.LOCAL_UNKNOWN_ZONE, row.unit(),
                    reason(row, "local time has no timezone"));
        }
        int minutes = userOffset.getAsInt();
        if (!validOffset(minutes)) {
            return RecordingClock.unordered(
                    row.measurementId(), row.source(), ClockKind.LOCAL_UNKNOWN_ZONE, row.unit(),
                    "supplied timezone offset is outside ±14 hours");
        }
        Long utc = civil != null ? civilToUtc(civil, minutes) : subtractOffset(localEpochNanos, minutes);
        if (utc == null || utc <= 0L) {
            return RecordingClock.unordered(
                    row.measurementId(), row.source(), ClockKind.LOCAL_UNKNOWN_ZONE, row.unit(),
                    "supplied timezone offset could not be applied");
        }
        return comparable(row, ClockKind.LOCAL_WITH_OFFSET, minutes, utc,
                "local time converted with a supplied offset");
    }

    private static RecordingClock comparable(
            TimestampObservation row,
            ClockKind kind,
            Integer offsetMinutes,
            long utc,
            String reason
    ) {
        return new RecordingClock(
                row.measurementId(), row.source(), kind, row.unit(), offsetMinutes, utc,
                ClockStatus.COMPARABLE, reason);
    }

    static boolean validOffset(Integer minutes) {
        return minutes != null && minutes >= -MAX_OFFSET_MINUTES && minutes <= MAX_OFFSET_MINUTES;
    }

    private static Long subtractOffset(long localEpochNanos, int offsetMinutes) {
        if (localEpochNanos <= 0L) {
            return null;
        }
        try {
            long delta = Math.multiplyExact(offsetMinutes, NANOS_PER_MINUTE);
            return Math.subtractExact(localEpochNanos, delta);
        } catch (ArithmeticException ex) {
            return null;
        }
    }

    private static Long civilToUtc(LocalDateTime civil, int offsetMinutes) {
        try {
            var instant = civil.atOffset(ZoneOffset.ofTotalSeconds(offsetMinutes * 60)).toInstant();
            return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L), instant.getNano());
        } catch (DateTimeException | ArithmeticException ex) {
            return null;
        }
    }

    private static String reason(TimestampObservation row, String fallback) {
        return row.detail() == null || row.detail().isBlank() ? fallback : row.detail();
    }
}
