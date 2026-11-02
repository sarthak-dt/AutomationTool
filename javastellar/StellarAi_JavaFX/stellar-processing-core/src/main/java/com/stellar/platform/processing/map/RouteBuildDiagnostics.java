package com.stellar.platform.processing.map;

/**
 * Bounded GPS-route build summary. Counts only — never a per-sample dump.
 */
public record RouteBuildDiagnostics(
        int rawLatitudeSamples,
        int rawLongitudeSamples,
        int pairedPoints,
        int acceptedPoints,
        int rejectedInvalidCoordinates,
        int rejectedTimestampPairs,
        int duplicateOrOutOfOrderTimestamps,
        int negativeOrZeroDt,
        int largeDiscontinuities,
        Long firstTimestampNanos,
        Double firstLatitude,
        Double firstLongitude,
        Long lastTimestampNanos,
        Double lastLatitude,
        Double lastLongitude,
        double largestSegmentDistanceMeters,
        Double largestDerivedSpeedKph
) {
    public static RouteBuildDiagnostics empty() {
        return new RouteBuildDiagnostics(
                0, 0, 0, 0, 0, 0, 0, 0, 0,
                null, null, null, null, null, null,
                0.0, null);
    }

    public String summary() {
        return "rawLat=" + rawLatitudeSamples
                + " rawLon=" + rawLongitudeSamples
                + " paired=" + pairedPoints
                + " accepted=" + acceptedPoints
                + " rejectedInvalid=" + rejectedInvalidCoordinates
                + " rejectedTimestampPairs=" + rejectedTimestampPairs
                + " dupOrOutOfOrderTs=" + duplicateOrOutOfOrderTimestamps
                + " negativeOrZeroDt=" + negativeOrZeroDt
                + " largeDiscontinuities=" + largeDiscontinuities
                + " first=" + formatPoint(firstTimestampNanos, firstLatitude, firstLongitude)
                + " last=" + formatPoint(lastTimestampNanos, lastLatitude, lastLongitude)
                + " largestStepM=" + formatNumber(largestSegmentDistanceMeters)
                + " largestDerivedKph=" + (largestDerivedSpeedKph == null
                ? "-" : formatNumber(largestDerivedSpeedKph));
    }

    private static String formatPoint(Long ts, Double lat, Double lon) {
        if (ts == null || lat == null || lon == null) {
            return "-";
        }
        return String.format(java.util.Locale.ROOT, "t=%.3fs lat=%.7f lon=%.7f", ts / 1e9, lat, lon);
    }

    private static String formatNumber(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }
}
