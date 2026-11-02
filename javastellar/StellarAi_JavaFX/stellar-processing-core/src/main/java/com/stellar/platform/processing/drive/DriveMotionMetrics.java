package com.stellar.platform.processing.drive;

import com.stellar.platform.processing.map.MapRouteBuildOptions;
import com.stellar.platform.processing.map.MapRouteBuilder;
import com.stellar.platform.processing.map.MapRouteModel;
import com.stellar.platform.processing.map.RouteRangeMetrics;

/**
 * GPS-track motion for an A→B interval. Average speed is distance / total duration (includes stops).
 * Moving average excludes {@link #stoppedDurationNanos()}.
 */
public record DriveMotionMetrics(
        RouteRangeMetrics range,
        long stoppedDurationNanos,
        Double movingAverageSpeedKph
) {
    public static DriveMotionMetrics compute(MapRouteModel route,
                                             int indexA,
                                             int indexB,
                                             DriveSegmentThresholds thresholds,
                                             MapRouteBuildOptions options) {
        RouteRangeMetrics range = RouteRangeMetrics.compute(route, indexA, indexB, options);
        if (route == null || route.isEmpty() || range.sampleCount() < 2) {
            return new DriveMotionMetrics(range, 0L, null);
        }
        DriveSegmentThresholds limits = thresholds == null ? DriveSegmentThresholds.defaults() : thresholds;
        int lo = Math.max(0, Math.min(indexA, indexB));
        int hi = Math.min(route.size() - 1, Math.max(indexA, indexB));
        double stopMps = limits.stopSpeedKph() / 3.6;
        long stopped = 0L;
        for (int i = lo + 1; i <= hi; i++) {
            if (route.segmentBreakBefore(i)) {
                continue;
            }
            long dt = route.timestampNanos(i) - route.timestampNanos(i - 1);
            if (dt < MapRouteBuilder.MIN_VALID_DT_NANOS) {
                continue;
            }
            double step = route.cumulativeDistanceMeters(i) - route.cumulativeDistanceMeters(i - 1);
            double mps = step / (dt / 1e9);
            if (mps <= stopMps) {
                stopped += dt;
            }
        }
        Double moving = null;
        long movingNanos = range.durationNanos() - stopped;
        if (movingNanos > 0 && range.distanceMeters() > 0) {
            moving = (range.distanceMeters() / (movingNanos / 1e9)) * 3.6;
        }
        return new DriveMotionMetrics(range, Math.max(0L, stopped), moving);
    }
}
