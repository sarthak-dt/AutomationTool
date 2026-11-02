package com.stellar.platform.processing.map.time;

/**
 * Normalized recording clock for one measurement.
 * {@code utcStartNanos} is set only when the clock is comparable across files.
 */
public record RecordingClock(
        String measurementId,
        TimestampSource source,
        ClockKind kind,
        TimeUnitKind unit,
        Integer offsetMinutes,
        Long utcStartNanos,
        ClockStatus status,
        String reason
) {
    public RecordingClock {
        measurementId = measurementId == null ? "" : measurementId;
        source = source == null ? TimestampSource.NONE : source;
        kind = kind == null ? ClockKind.MISSING : kind;
        unit = unit == null ? TimeUnitKind.NONE : unit;
        status = status == null ? ClockStatus.UNORDERED : status;
        reason = reason == null ? "" : reason;
    }

    public boolean comparable() {
        return status == ClockStatus.COMPARABLE && utcStartNanos != null;
    }

    public static RecordingClock unordered(
            String measurementId,
            TimestampSource source,
            ClockKind kind,
            TimeUnitKind unit,
            String reason
    ) {
        return new RecordingClock(
                measurementId, source, kind, unit, null, null, ClockStatus.UNORDERED, reason);
    }
}
