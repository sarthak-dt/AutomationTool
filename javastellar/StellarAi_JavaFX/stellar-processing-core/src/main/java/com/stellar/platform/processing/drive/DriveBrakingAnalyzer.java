package com.stellar.platform.processing.drive;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.graph.GraphSeriesQuery;
import com.stellar.platform.processing.map.MapRouteBuilder;
import com.stellar.platform.processing.map.MapRouteModel;

import java.util.List;

/**
 * Measured braking uses a mapped brake signal. GPS deceleration is reported separately as inferred.
 */
public final class DriveBrakingAnalyzer {

    public static final int MAX_SAMPLES = 8_192;

    public DriveBrakingSummary analyze(GraphSeriesQuery query,
                                       SignalCatalogEntry brakeEntry,
                                       MapRouteModel route,
                                       int indexA,
                                       int indexB,
                                       DriveSegmentThresholds thresholds) {
        DriveSegmentThresholds limits = thresholds == null ? DriveSegmentThresholds.defaults() : thresholds;
        if (brakeEntry != null && query != null && route != null && !route.isEmpty()) {
            int lo = Math.max(0, Math.min(indexA, indexB));
            int hi = Math.min(route.size() - 1, Math.max(indexA, indexB));
            DriveBrakingSummary measured = measured(
                    query, brakeEntry,
                    route.timestampNanos(lo), route.timestampNanos(hi),
                    route, lo, hi, limits);
            if (measured.eventCount() >= 0) {
                return measured;
            }
        }
        return inferred(route, indexA, indexB, limits);
    }

    private static DriveBrakingSummary measured(GraphSeriesQuery query,
                                                SignalCatalogEntry entry,
                                                long startNanos,
                                                long endNanos,
                                                MapRouteModel route,
                                                int lo,
                                                int hi,
                                                DriveSegmentThresholds limits) {
        List<SignalSamplePoint> samples = query.queryRange(entry.key(), startNanos, endNanos, MAX_SAMPLES);
        if (samples == null || samples.size() < 2) {
            return inferred(route, lo, hi, limits);
        }
        double threshold = activeThreshold(samples);
        int events = 0;
        long activeNanos = 0;
        boolean inEvent = false;
        int previousEligible = -1;
        for (int i = 0; i < samples.size(); i++) {
            if (!samples.get(i).usableMeasurement()) {
                inEvent = false;
                continue;
            }
            boolean active = samples.get(i).physicalValue() > threshold;
            if (active && !inEvent) {
                events++;
                inEvent = true;
            } else if (!active) {
                inEvent = false;
            }
            if (active && previousEligible == i - 1) {
                long dt = samples.get(i).timestampNanos() - samples.get(previousEligible).timestampNanos();
                if (dt > 0) {
                    activeNanos += dt;
                }
            }
            previousEligible = i;
        }
        Integer harsh = harshFromTrack(route, lo, hi, limits);
        return DriveBrakingSummary.measured(events, activeNanos, harsh, entry.signalName());
    }

    private static DriveBrakingSummary inferred(MapRouteModel route,
                                                int indexA,
                                                int indexB,
                                                DriveSegmentThresholds limits) {
        if (route == null || route.isEmpty()) {
            return DriveBrakingSummary.none();
        }
        int lo = Math.max(0, Math.min(indexA, indexB));
        int hi = Math.min(route.size() - 1, Math.max(indexA, indexB));
        EventCounts counts = decelerationEvents(route, lo, hi, limits.inferredBrakeDecelMps2(), limits.harshBrakeDecelMps2());
        return DriveBrakingSummary.inferred(counts.events, counts.activeNanos, counts.harsh);
    }

    private static Integer harshFromTrack(MapRouteModel route, int lo, int hi, DriveSegmentThresholds limits) {
        if (limits.harshBrakeDecelMps2() == null) {
            return null;
        }
        return decelerationEvents(route, lo, hi, limits.harshBrakeDecelMps2(), limits.harshBrakeDecelMps2()).events;
    }

    private static EventCounts decelerationEvents(MapRouteModel route,
                                                  int lo,
                                                  int hi,
                                                  double eventDecelMps2,
                                                  Double harshDecelMps2) {
        int events = 0;
        int harsh = 0;
        long active = 0;
        boolean inEvent = false;
        boolean inHarsh = false;
        for (int i = lo + 1; i <= hi; i++) {
            if (route.segmentBreakBefore(i)) {
                inEvent = false;
                inHarsh = false;
                continue;
            }
            long dtNanos = route.timestampNanos(i) - route.timestampNanos(i - 1);
            if (dtNanos < MapRouteBuilder.MIN_VALID_DT_NANOS) {
                continue;
            }
            double dt = dtNanos / 1e9;
            long prevDtNanos = i - 1 > lo && !route.segmentBreakBefore(i - 1)
                    ? route.timestampNanos(i - 1) - route.timestampNanos(i - 2)
                    : dtNanos;
            double prevDt = prevDtNanos >= MapRouteBuilder.MIN_VALID_DT_NANOS ? prevDtNanos / 1e9 : dt;
            double v1 = speedMps(route, i - 1, prevDt);
            double v2 = speedMps(route, i, dt);
            double accel = (v2 - v1) / dt;
            if (!Double.isFinite(accel)) {
                inEvent = false;
                inHarsh = false;
                continue;
            }
            boolean braking = accel <= -eventDecelMps2;
            if (braking) {
                active += (long) (dt * 1e9);
                if (!inEvent) {
                    events++;
                    inEvent = true;
                }
            } else {
                inEvent = false;
            }
            boolean harshNow = harshDecelMps2 != null && accel <= -harshDecelMps2;
            if (harshNow && !inHarsh) {
                harsh++;
                inHarsh = true;
            } else if (!harshNow) {
                inHarsh = false;
            }
        }
        return new EventCounts(events, active, harshDecelMps2 == null ? null : harsh);
    }

    private static double speedMps(MapRouteModel route, int index, double dtHint) {
        if (index <= 0) {
            return 0;
        }
        double dt = (route.timestampNanos(index) - route.timestampNanos(index - 1)) / 1e9;
        if (dt < MapRouteBuilder.MIN_VALID_DT_NANOS / 1e9) {
            dt = dtHint;
        }
        if (dt < MapRouteBuilder.MIN_VALID_DT_NANOS / 1e9) {
            return 0;
        }
        return (route.cumulativeDistanceMeters(index) - route.cumulativeDistanceMeters(index - 1)) / dt;
    }

    private static double activeThreshold(List<SignalSamplePoint> samples) {
        double max = 0;
        for (SignalSamplePoint sample : samples) {
            if (sample.usableMeasurement()) {
                max = Math.max(max, sample.physicalValue());
            }
        }
        if (max <= 1.5) {
            return 0.15;
        }
        if (max <= 100) {
            return Math.max(1.0, max * 0.05);
        }
        return max * 0.05;
    }

    private record EventCounts(int events, long activeNanos, Integer harsh) {
    }
}
