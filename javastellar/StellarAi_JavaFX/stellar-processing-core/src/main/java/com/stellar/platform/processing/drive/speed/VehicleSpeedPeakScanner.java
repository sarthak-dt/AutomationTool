package com.stellar.platform.processing.drive.speed;

import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.graph.GraphSeriesQuery;
import com.stellar.platform.processing.map.MapRouteBuilder;

import java.util.ArrayDeque;

/**
 * Bounded peak of one physical speed series. Streaming: O(window), never the whole waveform.
 *
 * <p>Rejects isolated discontinuous excursions. Does not apply a road-speed cap: a sustained
 * high-speed event remains valid.
 */
public final class VehicleSpeedPeakScanner {

    public static final int WINDOW = 5;
    public static final int PAGE_HINT = 4_096;
    /** Continuity bound on single-sample acceleration, not a maximum speed. */
    static final double ISOLATED_ACCEL_MPS2 = 30.0;
    static final double ISOLATED_JUMP_KPH = 25.0;

    private VehicleSpeedPeakScanner() {
    }

    public static QualityAwarePeak scan(GraphSeriesQuery query,
                                        SignalCatalogKey key,
                                        SpeedUnit unit,
                                        long startNanos,
                                        long endNanos) {
        if (query == null || key == null || unit == null || endNanos < startNanos) {
            return QualityAwarePeak.none(SpeedQualityAssessment.empty(), "No speed samples in range.");
        }
        ArrayDeque<Sample> window = new ArrayDeque<>(WINDOW + 1);
        PeakState state = new PeakState();
        query.forEachSample(key, startNanos, endNanos, sample -> {
            consider(window, state, sample, unit);
            return true;
        });
        flush(window, state);
        SpeedQualityAssessment quality = new SpeedQualityAssessment(
                state.visited, state.rejected, state.duplicates, state.nonFinite, WINDOW, coverage(state, startNanos, endNanos));
        if (state.bestKph == null) {
            return QualityAwarePeak.none(quality, state.visited == 0
                    ? "Selected speed source has no samples in this range."
                    : "No continuity-valid speed sample in this range.");
        }
        return new QualityAwarePeak(state.bestKph, state.bestTs, quality, "Quality-valid peak of the selected source.");
    }

    private static void consider(ArrayDeque<Sample> window, PeakState state, SignalSamplePoint point, SpeedUnit unit) {
        state.visited++;
        if (point == null || !point.usableMeasurement()) {
            state.nonFinite++;
            flush(window, state);
            window.clear();
            return;
        }
        double kph = unit.toKilometresPerHour(point.physicalValue());
        if (!Double.isFinite(kph)) {
            state.nonFinite++;
            return;
        }
        Sample last = window.peekLast();
        if (last != null) {
            long dt = point.timestampNanos() - last.timestampNanos;
            if (dt < MapRouteBuilder.MIN_VALID_DT_NANOS) {
                state.duplicates++;
                return;
            }
        }
        window.addLast(new Sample(point.timestampNanos(), kph));
        if (window.size() > WINDOW) {
            window.removeFirst();
        }
        if (window.size() >= 3) {
            Sample[] snap = window.toArray(Sample[]::new);
            int center = snap.length - 2;
            evaluate(snap, center, state);
        }
    }

    private static void flush(ArrayDeque<Sample> window, PeakState state) {
        if (window.size() == 1) {
            Sample only = window.peekFirst();
            accept(only, state);
            return;
        }
        if (window.size() == 2) {
            Sample[] snap = window.toArray(Sample[]::new);
            evaluatePair(snap[0], snap[1], state);
            return;
        }
        if (window.size() >= 3) {
            Sample[] snap = window.toArray(Sample[]::new);
            evaluate(snap, snap.length - 1, state);
        }
    }

    private static void evaluatePair(Sample a, Sample b, PeakState state) {
        if (isolatedJump(a, b, null)) {
            state.rejected++;
            accept(a, state);
            return;
        }
        accept(a, state);
        accept(b, state);
    }

    private static void evaluate(Sample[] snap, int center, PeakState state) {
        Sample prev = center > 0 ? snap[center - 1] : null;
        Sample cur = snap[center];
        Sample next = center + 1 < snap.length ? snap[center + 1] : null;
        if (isolatedSpike(prev, cur, next) || isolatedJump(prev, cur, next)) {
            state.rejected++;
            return;
        }
        accept(cur, state);
    }

    private static boolean isolatedSpike(Sample prev, Sample cur, Sample next) {
        if (prev == null || next == null) {
            return false;
        }
        double left = Math.abs(cur.kph - prev.kph);
        double right = Math.abs(cur.kph - next.kph);
        double neighbours = Math.abs(prev.kph - next.kph);
        return left >= ISOLATED_JUMP_KPH
                && right >= ISOLATED_JUMP_KPH
                && neighbours < left * 0.5
                && neighbours < right * 0.5;
    }

    private static boolean isolatedJump(Sample prev, Sample cur, Sample next) {
        if (prev == null || cur == null) {
            return false;
        }
        long dt = cur.timestampNanos - prev.timestampNanos;
        if (dt < MapRouteBuilder.MIN_VALID_DT_NANOS) {
            return true;
        }
        double accel = Math.abs((cur.kph - prev.kph) / 3.6) / (dt / 1e9);
        if (accel <= ISOLATED_ACCEL_MPS2) {
            return false;
        }
        if (next == null) {
            return true;
        }
        double back = Math.abs((next.kph - cur.kph) / 3.6)
                / Math.max(MapRouteBuilder.MIN_VALID_DT_NANOS / 1e9, (next.timestampNanos - cur.timestampNanos) / 1e9);
        return back > ISOLATED_ACCEL_MPS2 * 0.5 && Math.abs(next.kph - prev.kph) < Math.abs(cur.kph - prev.kph) * 0.5;
    }

    private static void accept(Sample sample, PeakState state) {
        if (sample == null) {
            return;
        }
        if (state.bestKph == null || sample.kph > state.bestKph) {
            state.bestKph = sample.kph;
            state.bestTs = sample.timestampNanos;
        }
    }

    private static double coverage(PeakState state, long start, long end) {
        if (state.visited <= 0 || end <= start) {
            return 0;
        }
        return 1.0;
    }

    private record Sample(long timestampNanos, double kph) {
    }

    private static final class PeakState {
        long visited;
        int rejected;
        int duplicates;
        int nonFinite;
        Double bestKph;
        Long bestTs;
    }
}
