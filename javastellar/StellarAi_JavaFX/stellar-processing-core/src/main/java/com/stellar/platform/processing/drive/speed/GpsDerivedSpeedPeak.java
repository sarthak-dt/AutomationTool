package com.stellar.platform.processing.drive.speed;

import com.stellar.platform.processing.map.MapRouteBuilder;
import com.stellar.platform.processing.map.MapRouteModel;

/**
 * Instantaneous GPS-derived speed peak with interval-quality checks. Does not mutate route geometry.
 */
public final class GpsDerivedSpeedPeak {

    static final double HELD_METERS = 0.15;
    static final double CATCH_UP_RATIO = 2.5;

    private GpsDerivedSpeedPeak() {
    }

    public static QualityAwarePeak scan(MapRouteModel route, int indexA, int indexB) {
        if (route == null || route.isEmpty() || route.size() < 2) {
            return QualityAwarePeak.none(SpeedQualityAssessment.empty(), "No GPS route for derived speed.");
        }
        int lo = Math.max(0, Math.min(indexA, indexB));
        int hi = Math.min(route.size() - 1, Math.max(indexA, indexB));
        Interval[] ring = new Interval[VehicleSpeedPeakScanner.WINDOW];
        int count = 0;
        PeakAcc acc = new PeakAcc();
        for (int i = lo + 1; i <= hi; i++) {
            acc.visited++;
            Interval interval = interval(route, i);
            if (interval == null) {
                continue;
            }
            if (count < ring.length) {
                ring[count++] = interval;
            } else {
                System.arraycopy(ring, 1, ring, 0, ring.length - 1);
                ring[ring.length - 1] = interval;
            }
            if (count >= 3) {
                evaluate(ring, count - 2, count, acc);
            }
        }
        if (count == 1) {
            accept(ring[0], acc);
        } else if (count == 2) {
            evaluatePair(ring[0], ring[1], acc);
        } else if (count >= 3) {
            evaluate(ring, count - 1, count, acc);
        }
        SpeedQualityAssessment quality = new SpeedQualityAssessment(
                acc.visited, acc.rejected, acc.duplicates, 0, VehicleSpeedPeakScanner.WINDOW, count > 0 ? 1.0 : 0);
        if (acc.bestKph == null) {
            return QualityAwarePeak.none(quality, "No continuity-valid GPS-derived speed interval.");
        }
        return new QualityAwarePeak(acc.bestKph, acc.bestTs, quality,
                "GPS-derived fallback after interval-quality validation.");
    }

    private static Interval interval(MapRouteModel route, int index) {
        if (route.segmentBreakBefore(index)) {
            return null;
        }
        long dtNanos = route.timestampNanos(index) - route.timestampNanos(index - 1);
        if (dtNanos < MapRouteBuilder.MIN_VALID_DT_NANOS) {
            return Interval.duplicate(route.timestampNanos(index));
        }
        double step = route.cumulativeDistanceMeters(index) - route.cumulativeDistanceMeters(index - 1);
        if (!Double.isFinite(step) || step < 0) {
            return null;
        }
        double kph = (step / (dtNanos / 1e9)) * 3.6;
        if (!Double.isFinite(kph)) {
            return null;
        }
        boolean held = step <= HELD_METERS;
        return new Interval(route.timestampNanos(index), kph, step, dtNanos, held, false);
    }

    private static void evaluatePair(Interval a, Interval b, PeakAcc acc) {
        if (a.duplicate) {
            acc.duplicates++;
        } else {
            accept(a, acc);
        }
        if (b.duplicate) {
            acc.duplicates++;
        } else if (catchUp(a, b, null) || isolated(a, b, null)) {
            acc.rejected++;
        } else {
            accept(b, acc);
        }
    }

    private static void evaluate(Interval[] ring, int center, int length, PeakAcc acc) {
        Interval prev = center > 0 ? ring[center - 1] : null;
        Interval cur = ring[center];
        Interval next = center + 1 < length ? ring[center + 1] : null;
        if (cur == null) {
            return;
        }
        if (cur.duplicate) {
            acc.duplicates++;
            return;
        }
        if (catchUp(prev, cur, next) || isolated(prev, cur, next)) {
            acc.rejected++;
            return;
        }
        accept(cur, acc);
    }

    /**
     * Held coordinate then a single catch-up displacement: geometry may keep the points;
     * instantaneous speed from that interval is not a defensible maximum.
     */
    static boolean catchUp(Interval prev, Interval cur, Interval next) {
        if (prev == null || cur == null || prev.duplicate || cur.duplicate) {
            return false;
        }
        if (!prev.held || cur.held) {
            return false;
        }
        if (next == null || next.duplicate) {
            return cur.kph >= VehicleSpeedPeakScanner.ISOLATED_JUMP_KPH;
        }
        if (next.held) {
            return cur.kph >= VehicleSpeedPeakScanner.ISOLATED_JUMP_KPH;
        }
        return cur.kph >= next.kph * 1.4
                && (cur.kph - next.kph) >= VehicleSpeedPeakScanner.ISOLATED_JUMP_KPH;
    }

    static boolean isolated(Interval prev, Interval cur, Interval next) {
        if (prev == null || next == null || cur == null) {
            return false;
        }
        if (prev.duplicate || next.duplicate || cur.duplicate) {
            return false;
        }
        double left = Math.abs(cur.kph - prev.kph);
        double right = Math.abs(cur.kph - next.kph);
        double neighbours = Math.abs(prev.kph - next.kph);
        return left >= VehicleSpeedPeakScanner.ISOLATED_JUMP_KPH
                && right >= VehicleSpeedPeakScanner.ISOLATED_JUMP_KPH
                && neighbours < left * 0.5
                && neighbours < right * 0.5;
    }

    private static void accept(Interval interval, PeakAcc acc) {
        if (interval == null || interval.duplicate || interval.held) {
            return;
        }
        if (acc.bestKph == null || interval.kph > acc.bestKph) {
            acc.bestKph = interval.kph;
            acc.bestTs = interval.timestampNanos;
        }
    }

    record Interval(long timestampNanos, double kph, double stepMeters, long dtNanos, boolean held, boolean duplicate) {
        static Interval duplicate(long timestampNanos) {
            return new Interval(timestampNanos, 0, 0, 0, false, true);
        }
    }

    private static final class PeakAcc {
        long visited;
        int rejected;
        int duplicates;
        Double bestKph;
        Long bestTs;
    }
}
