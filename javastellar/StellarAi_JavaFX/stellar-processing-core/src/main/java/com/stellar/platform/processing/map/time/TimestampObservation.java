package com.stellar.platform.processing.map.time;

import java.time.LocalDateTime;

/**
 * One clock found in a measurement, before it is judged comparable.
 * Sample {@code timestampNanos} values are never copied into this record.
 */
public record TimestampObservation(
        String measurementId,
        TimestampSource source,
        TimestampRawKind rawKind,
        TimeUnitKind unit,
        Integer offsetMinutes,
        boolean zoneExplicit,
        boolean localTime,
        long rawValueNanos,
        LocalDateTime civilLocal,
        String detail
) {
    public TimestampObservation {
        measurementId = measurementId == null ? "" : measurementId;
        source = source == null ? TimestampSource.NONE : source;
        rawKind = rawKind == null ? TimestampRawKind.UNKNOWN : rawKind;
        unit = unit == null ? TimeUnitKind.NONE : unit;
        detail = detail == null ? "" : detail;
    }

    public static TimestampObservation none(String measurementId, String detail) {
        return new TimestampObservation(
                measurementId, TimestampSource.NONE, TimestampRawKind.UNKNOWN, TimeUnitKind.NONE,
                null, false, false, 0L, null, detail);
    }
}
