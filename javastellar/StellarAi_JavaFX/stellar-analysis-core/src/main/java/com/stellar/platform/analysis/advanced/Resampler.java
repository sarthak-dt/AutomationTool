package com.stellar.platform.analysis.advanced;

import com.stellar.platform.parser.spi.CancellationToken;

import java.util.Arrays;

/**
 * Controlled numeric resampling. Text/enumerated signals must use PREVIOUS_VALUE_HOLD,
 * never LINEAR_INTERPOLATION.
 */
public final class Resampler {

    public record Result(long[] timestamps, double[] values, ResampleMethod method, double targetRateHz,
                         boolean resampled, long maxGapNanos, int rejectedGaps) {
    }

    private Resampler() {
    }

    public static Result resample(
            long[] srcT,
            double[] srcV,
            double targetRateHz,
            ResampleMethod method,
            long maxGapNanos,
            long alignOriginNanos,
            boolean discrete,
            CancellationToken token
    ) {
        AnalyticsLimits.requireFinite(targetRateHz, "targetRateHz");
        if (targetRateHz <= 0 || targetRateHz < AnalyticsLimits.MIN_TARGET_RATE_HZ) {
            throw new IllegalArgumentException("target sample rate must be > 0");
        }
        if (targetRateHz > AnalyticsLimits.MAX_TARGET_RATE_HZ) {
            throw new IllegalArgumentException("target sample rate exceeds " + AnalyticsLimits.MAX_TARGET_RATE_HZ + " Hz");
        }
        if (srcT == null || srcT.length < 2) {
            throw new IllegalArgumentException("resampling requires at least 2 samples");
        }
        if (discrete && method == ResampleMethod.LINEAR_INTERPOLATION) {
            throw new IllegalArgumentException("LINEAR_INTERPOLATION is not allowed for discrete/text signals");
        }
        if (maxGapNanos <= 0) {
            throw new IllegalArgumentException(
                    "Resampling requires an explicit maximum interpolation gap (maxGapNanos > 0). "
                            + "An omitted gap does not mean unlimited interpolation.");
        }
        long start = srcT[0];
        long end = srcT[srcT.length - 1];
        if (end <= start) {
            throw new IllegalArgumentException("source span is empty");
        }
        long step = Math.round(1e9 / targetRateHz);
        if (step <= 0) {
            throw new IllegalArgumentException("target rate produces a non-positive interval");
        }
        long origin = alignOriginNanos == 0L ? start : alignOriginNanos;
        long first = start;
        if (origin <= start) {
            long k = (start - origin + step - 1) / step;
            first = origin + k * step;
        }
        if (first < start) {
            first = start;
        }
        long estimated = (end - first) / step + 1;
        if (estimated > AnalyticsLimits.MAX_RESAMPLE_OUTPUT) {
            throw new IllegalArgumentException(
                    "Resample would produce " + estimated + " samples (limit "
                            + AnalyticsLimits.MAX_RESAMPLE_OUTPUT
                            + "). Narrow the window or lower the target rate.");
        }
        int n = (int) estimated;
        long[] outT = new long[n];
        double[] outV = new double[n];
        int j = 0;
        int rejected = 0;
        int written = 0;
        for (int i = 0; i < n; i++) {
            if (token != null && (i & 0x3FF) == 0 && token.isCancellationRequested()) {
                throw new com.stellar.platform.analysis.source.AnalysisCancelledException();
            }
            long t = first + (long) i * step;
            if (t > end) {
                break;
            }
            while (j + 1 < srcT.length && srcT[j + 1] <= t) {
                j++;
            }
            long gap = 0L;
            if (j + 1 < srcT.length) {
                gap = srcT[j + 1] - srcT[j];
            }
            if (maxGapNanos > 0 && gap > maxGapNanos && t > srcT[j] && j + 1 < srcT.length && t < srcT[j + 1]) {
                rejected++;
                continue;
            }
            outT[written] = t;
            outV[written] = interpolate(srcT, srcV, j, t, method);
            written++;
        }
        if (written != n) {
            outT = Arrays.copyOf(outT, written);
            outV = Arrays.copyOf(outV, written);
        }
        return new Result(outT, outV, method, targetRateHz, true, maxGapNanos, rejected);
    }

    private static double interpolate(long[] t, double[] v, int j, long target, ResampleMethod method) {
        if (target <= t[0]) {
            return v[0];
        }
        if (target >= t[t.length - 1]) {
            return v[v.length - 1];
        }
        int i = j;
        if (i < 0) {
            i = 0;
        }
        if (i >= t.length - 1) {
            i = t.length - 2;
        }
        while (i + 1 < t.length && t[i + 1] < target) {
            i++;
        }
        return switch (method) {
            case PREVIOUS_VALUE_HOLD -> v[i];
            case NEAREST -> {
                long dl = Math.abs(target - t[i]);
                long dr = Math.abs(t[i + 1] - target);
                yield dr < dl ? v[i + 1] : v[i];
            }
            case LINEAR_INTERPOLATION -> {
                long dt = t[i + 1] - t[i];
                if (dt <= 0) {
                    yield v[i];
                }
                double a = (double) (target - t[i]) / (double) dt;
                yield v[i] + a * (v[i + 1] - v[i]);
            }
        };
    }
}
