package com.stellar.platform.processing.time;

/**
 * Cursor shared by the graph and the GPS route.
 * Identity is the measurement source id, never the display name.
 * {@code relativeTimestampNanos} is the stored sample time inside that measurement.
 * {@code absoluteTimestampNanos} is set only when a verified recording clock exists.
 * Lookup must use the measurement id plus the relative time, not the absolute time alone.
 */
public record MeasurementCursor(
        String measurementId,
        long relativeTimestampNanos,
        Long absoluteTimestampNanos,
        boolean clockComparable,
        long token,
        boolean placeMarker
) {
    public static final MeasurementCursor EMPTY = new MeasurementCursor("", 0L, null, false, 0L, false);

    /** Shown when the shared timeline has no recording at the selected instant. */
    public static final String NO_MEASUREMENT_AT_TIME = "No measurement at selected time";

    public MeasurementCursor {
        measurementId = measurementId == null ? "" : measurementId.trim();
    }

    public boolean hasMeasurement() {
        return !measurementId.isBlank();
    }

    /** Same measurement and stored time. The token is allowed to differ. */
    public boolean samePlace(MeasurementCursor other) {
        if (other == null) {
            return false;
        }
        return measurementId.equalsIgnoreCase(other.measurementId)
                && relativeTimestampNanos == other.relativeTimestampNanos
                && placeMarker == other.placeMarker
                && clockComparable == other.clockComparable
                && java.util.Objects.equals(absoluteTimestampNanos, other.absoluteTimestampNanos);
    }
}
