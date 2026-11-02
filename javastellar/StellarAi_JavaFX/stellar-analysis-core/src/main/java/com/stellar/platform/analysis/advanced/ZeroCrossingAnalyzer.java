package com.stellar.platform.analysis.advanced;

import java.util.ArrayList;
import java.util.List;

public final class ZeroCrossingAnalyzer {

    public record Result(
            CrossingMode mode,
            List<Long> timestamps,
            int count,
            double averageIntervalSeconds,
            double approximateFrequencyHz
    ) {
    }

    private ZeroCrossingAnalyzer() {
    }

    public static Result analyze(long[] t, double[] v, CrossingMode mode) {
        CrossingMode m = mode == null ? CrossingMode.ALL : mode;
        if (t == null || v == null || t.length != v.length || t.length < 2) {
            return new Result(m, List.of(), 0, Double.NaN, Double.NaN);
        }
        List<Long> crosses = new ArrayList<>();
        for (int i = 1; i < v.length; i++) {
            double a = v[i - 1];
            double b = v[i];
            if (!Double.isFinite(a) || !Double.isFinite(b)) {
                continue;
            }
            if (a == 0d && b == 0d) {
                continue;
            }
            boolean pos = a < 0d && b >= 0d;
            boolean neg = a > 0d && b <= 0d;
            if ((m == CrossingMode.POSITIVE && pos)
                    || (m == CrossingMode.NEGATIVE && neg)
                    || (m == CrossingMode.ALL && (pos || neg))) {
                crosses.add(interpolateTime(t[i - 1], a, t[i], b));
            }
        }
        double avg = Double.NaN;
        double freq = Double.NaN;
        if (crosses.size() >= 2) {
            long span = crosses.getLast() - crosses.getFirst();
            avg = (span / 1e9) / (crosses.size() - 1);
            if (avg > 0) {
                // ALL counts both edges, so the oscillation frequency is half the crossing rate.
                freq = (m == CrossingMode.ALL ? 0.5d : 1d) / avg;
            }
        }
        return new Result(m, List.copyOf(crosses), crosses.size(), avg, freq);
    }

    static long interpolateTime(long t0, double v0, long t1, double v1) {
        double dv = v1 - v0;
        if (dv == 0d) {
            return t0;
        }
        double a = -v0 / dv;
        a = Math.min(1d, Math.max(0d, a));
        return t0 + Math.round(a * (t1 - t0));
    }
}
