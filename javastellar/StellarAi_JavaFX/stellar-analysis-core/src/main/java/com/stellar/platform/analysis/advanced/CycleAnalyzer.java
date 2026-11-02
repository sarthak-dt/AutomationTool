package com.stellar.platform.analysis.advanced;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounded cycle segmentation. Cycle boundaries are explicit: rising zero-crossings
 * or supplied event timestamps. Never invents a cycle definition.
 */
public final class CycleAnalyzer {

    public enum Criterion {
        RISING_ZERO_CROSSING,
        EVENT_TIMESTAMPS
    }

    public record Cycle(
            int index,
            long startNanos,
            long endNanos,
            double durationSeconds,
            double min,
            double max,
            double mean,
            double rms,
            double peakToPeak
    ) {
    }

    public record Result(Criterion criterion, List<Cycle> cycles) {
    }

    private CycleAnalyzer() {
    }

    public static Result fromRisingZeroCrossings(long[] t, double[] v) {
        ZeroCrossingAnalyzer.Result zc = ZeroCrossingAnalyzer.analyze(t, v, CrossingMode.POSITIVE);
        return fromBoundaries(t, v, zc.timestamps(), Criterion.RISING_ZERO_CROSSING);
    }

    public static Result fromBoundaries(long[] t, double[] v, List<Long> boundaries, Criterion criterion) {
        if (t == null || v == null || boundaries == null || boundaries.size() < 2) {
            return new Result(criterion, List.of());
        }
        List<Cycle> cycles = new ArrayList<>();
        int i = 0;
        for (int c = 0; c < boundaries.size() - 1; c++) {
            long start = boundaries.get(c);
            long end = boundaries.get(c + 1);
            while (i < t.length && t[i] < start) {
                i++;
            }
            int from = i;
            int to = i;
            while (to < t.length && t[to] <= end) {
                to++;
            }
            if (to - from < 2) {
                continue;
            }
            cycles.add(summarize(cycles.size(), start, end, v, from, to));
            i = from;
        }
        return new Result(criterion, List.copyOf(cycles));
    }

    private static Cycle summarize(int index, long start, long end, double[] v, int from, int to) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double sum = 0d;
        double sq = 0d;
        int n = 0;
        for (int i = from; i < to; i++) {
            if (!Double.isFinite(v[i])) {
                continue;
            }
            min = Math.min(min, v[i]);
            max = Math.max(max, v[i]);
            sum += v[i];
            sq += v[i] * v[i];
            n++;
        }
        double mean = n == 0 ? Double.NaN : sum / n;
        double rms = n == 0 ? Double.NaN : Math.sqrt(sq / n);
        return new Cycle(index, start, end, (end - start) / 1e9, min, max, mean, rms, max - min);
    }
}
