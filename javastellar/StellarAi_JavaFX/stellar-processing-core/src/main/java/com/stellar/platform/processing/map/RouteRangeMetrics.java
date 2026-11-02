package com.stellar.platform.processing.map;

/**
 * Summary of an A→B route segment.
 *
 * <p>Optional fields are {@code null} when the measurement lacks the required data, so the UI can
 * hide the row rather than display a fabricated zero.
 *
 * <p>The speeds are derived from the GPS track — successive coordinate deltas over time — and are
 * not the mapped GPS Speed signal. {@code maxSpeedKph} is a route-derived instantaneous reference,
 * not product-facing Maximum Speed. Callers that display Maximum Speed must use
 * {@code VehicleSpeedSourceResolver}.
 */
public record RouteRangeMetrics(
        long startTimestampNanos,
        long endTimestampNanos,
        long durationNanos,
        double distanceMeters,
        Double averageSpeedKph,
        Double maxSpeedKph,
        Double elevationGainMeters,
        int sampleCount
) {

    public double durationSeconds() {
        return durationNanos / 1e9;
    }

    public double distanceKilometers() {
        return distanceMeters / 1000.0;
    }

    /**
     * Computes range metrics from indexed route data only.
     *
     * <p>Distance is an O(1) cumulative-index difference; speed and elevation are a single bounded
     * pass over the selected range, never over the whole route.
     */
    public static RouteRangeMetrics compute(MapRouteModel route, int indexA, int indexB, MapRouteBuildOptions options) {
        if (route == null || route.isEmpty()) {
            return new RouteRangeMetrics(0, 0, 0, 0, null, null, null, 0);
        }
        MapRouteBuildOptions opts = options == null ? MapRouteBuildOptions.defaults() : options;
        int lo = Math.max(0, Math.min(indexA, indexB));
        int hi = Math.min(route.size() - 1, Math.max(indexA, indexB));

        long start = route.timestampNanos(lo);
        long end = route.timestampNanos(hi);
        long duration = end - start;
        double distance = route.distanceMetersBetween(lo, hi);
        int samples = hi - lo + 1;

        Double average = duration > 0 && distance > 0
                ? (distance / (duration / 1e9)) * 3.6
                : null;

        Double maxSpeed = null;
        for (int i = lo + 1; i <= hi; i++) {
            if (route.segmentBreakBefore(i)) {
                continue;
            }
            long dtNanos = route.timestampNanos(i) - route.timestampNanos(i - 1);
            if (dtNanos < MapRouteBuilder.MIN_VALID_DT_NANOS) {
                continue;
            }
            double dt = dtNanos / 1e9;
            double step = route.cumulativeDistanceMeters(i) - route.cumulativeDistanceMeters(i - 1);
            double kph = (step / dt) * 3.6;
            if (!Double.isFinite(kph)) {
                continue;
            }
            if (maxSpeed == null || kph > maxSpeed) {
                maxSpeed = kph;
            }
        }

        Double elevationGain = route.hasAltitude() ? elevationGain(route, lo, hi, opts.elevationNoiseMeters()) : null;

        return new RouteRangeMetrics(start, end, duration, distance, average, maxSpeed, elevationGain, samples);
    }

    /**
     * Hysteresis accumulation: a climb only counts once it exceeds the noise threshold above the
     * last confirmed low point, so GPS altitude jitter cannot inflate the total.
     */
    private static double elevationGain(MapRouteModel route, int lo, int hi, double noiseMeters) {
        double gain = 0;
        Double reference = null;
        boolean climbing = false;
        for (int i = lo; i <= hi; i++) {
            double altitude = route.altitude(i);
            if (Double.isNaN(altitude)) {
                continue;
            }
            if (reference == null) {
                reference = altitude;
                continue;
            }
            double delta = altitude - reference;
            if (delta > noiseMeters) {
                gain += delta;
                reference = altitude;
                climbing = true;
            } else if (delta < -noiseMeters || (!climbing && delta < 0)) {
                reference = altitude;
                climbing = false;
            }
        }
        return gain;
    }
}
