package com.stellar.platform.analysis.advanced;

import com.stellar.platform.analysis.source.AnalysisCancelledException;
import com.stellar.platform.parser.spi.CancellationToken;

import java.util.ArrayDeque;

public final class TimeDomainTransforms {

    private TimeDomainTransforms() {
    }

    public static double[] movingAverage(
            long[] t,
            double[] v,
            double windowSize,
            WindowUnit unit,
            CancellationToken token
    ) {
        requireSeries(t, v);
        AnalyticsLimits.requireFinite(windowSize, "window");
        if (!(windowSize > 0)) {
            throw new IllegalArgumentException("moving-average window must be > 0");
        }
        double[] out = new double[v.length];
        if (unit == WindowUnit.SAMPLES) {
            int w = (int) Math.round(windowSize);
            if (w < 1) {
                throw new IllegalArgumentException("sample window must be at least 1");
            }
            double sum = 0d;
            for (int i = 0; i < v.length; i++) {
                cancel(token, i);
                sum += v[i];
                if (i >= w) {
                    sum -= v[i - w];
                }
                int n = Math.min(i + 1, w);
                out[i] = sum / n;
            }
            return out;
        }
        long win = toNanos(windowSize, unit);
        int left = 0;
        double sum = 0d;
        for (int i = 0; i < v.length; i++) {
            cancel(token, i);
            sum += v[i];
            while (left < i && t[i] - t[left] > win) {
                sum -= v[left];
                left++;
            }
            out[i] = sum / (i - left + 1);
        }
        return out;
    }

    public static double[] exponentialSmoothing(double[] v, double alpha, CancellationToken token) {
        if (v == null || v.length == 0) {
            throw new IllegalArgumentException("empty series");
        }
        AnalyticsLimits.requireFinite(alpha, "alpha");
        if (!(alpha > 0d && alpha <= 1d)) {
            throw new IllegalArgumentException("alpha must satisfy 0 < alpha <= 1");
        }
        double[] out = new double[v.length];
        out[0] = v[0];
        for (int i = 1; i < v.length; i++) {
            cancel(token, i);
            out[i] = alpha * v[i] + (1d - alpha) * out[i - 1];
        }
        return out;
    }

    public static double[] exponentialFromTimeConstant(long[] t, double[] v, double tauSeconds, CancellationToken token) {
        AnalyticsLimits.requireFinite(tauSeconds, "timeConstant");
        if (!(tauSeconds > 0)) {
            throw new IllegalArgumentException("time constant must be > 0");
        }
        requireSeries(t, v);
        double[] out = new double[v.length];
        out[0] = v[0];
        for (int i = 1; i < v.length; i++) {
            cancel(token, i);
            double dt = (t[i] - t[i - 1]) / 1e9;
            double a = 1d - Math.exp(-dt / tauSeconds);
            a = Math.min(1d, Math.max(0d, a));
            out[i] = a * v[i] + (1d - a) * out[i - 1];
        }
        return out;
    }

    public static double[] envelope(long[] t, double[] v, double windowSize, WindowUnit unit,
                                   EnvelopeMode mode, CancellationToken token) {
        requireSeries(t, v);
        AnalyticsLimits.requireFinite(windowSize, "envelopeWindow");
        if (!(windowSize > 0)) {
            throw new IllegalArgumentException("envelope window must be > 0");
        }
        EnvelopeMode m = mode == null ? EnvelopeMode.ABS_PEAK : mode;
        if (unit == WindowUnit.SAMPLES) {
            int w = Math.max(1, (int) Math.round(windowSize));
            double[] out = new double[v.length];
            for (int i = 0; i < v.length; i++) {
                cancel(token, i);
                int from = Math.max(0, i - w + 1);
                out[i] = windowStat(v, from, i + 1, m);
            }
            return out;
        }
        long win = toNanos(windowSize, unit);
        double[] out = new double[v.length];
        int left = 0;
        ArrayDeque<Integer> peak = new ArrayDeque<>();
        double sumSq = 0d;
        for (int i = 0; i < v.length; i++) {
            cancel(token, i);
            if (m == EnvelopeMode.RMS) {
                sumSq += v[i] * v[i];
            } else {
                while (!peak.isEmpty() && Math.abs(v[peak.peekLast()]) <= Math.abs(v[i])) {
                    peak.removeLast();
                }
                peak.addLast(i);
            }
            while (left < i && t[i] - t[left] > win) {
                if (m == EnvelopeMode.RMS) {
                    sumSq -= v[left] * v[left];
                } else if (!peak.isEmpty() && peak.peekFirst() == left) {
                    peak.removeFirst();
                }
                left++;
            }
            int n = i - left + 1;
            out[i] = m == EnvelopeMode.RMS
                    ? Math.sqrt(Math.max(0d, sumSq) / n)
                    : Math.abs(v[peak.peekFirst()]);
        }
        return out;
    }

    public static double[] windowedStatistic(
            long[] t,
            double[] v,
            double windowSize,
            WindowUnit unit,
            WindowedStatistic stat,
            CancellationToken token
    ) {
        requireSeries(t, v);
        AnalyticsLimits.requireFinite(windowSize, "window");
        if (!(windowSize > 0)) {
            throw new IllegalArgumentException("windowed-statistic window must be > 0");
        }
        if (unit == WindowUnit.SAMPLES) {
            throw new IllegalArgumentException(
                    "windowed statistics use time windows. Specify MILLISECONDS or SECONDS, not a bare sample count.");
        }
        long win = toNanos(windowSize, unit);
        WindowedStatistic s = stat == null ? WindowedStatistic.RMS : stat;
        double[] out = new double[v.length];
        int left = 0;
        double sum = 0d;
        double sumSq = 0d;
        ArrayDeque<Integer> minq = new ArrayDeque<>();
        ArrayDeque<Integer> maxq = new ArrayDeque<>();
        for (int i = 0; i < v.length; i++) {
            cancel(token, i);
            sum += v[i];
            sumSq += v[i] * v[i];
            while (!minq.isEmpty() && v[minq.peekLast()] >= v[i]) {
                minq.removeLast();
            }
            minq.addLast(i);
            while (!maxq.isEmpty() && v[maxq.peekLast()] <= v[i]) {
                maxq.removeLast();
            }
            maxq.addLast(i);
            while (left < i && t[i] - t[left] > win) {
                sum -= v[left];
                sumSq -= v[left] * v[left];
                if (!minq.isEmpty() && minq.peekFirst() == left) {
                    minq.removeFirst();
                }
                if (!maxq.isEmpty() && maxq.peekFirst() == left) {
                    maxq.removeFirst();
                }
                left++;
            }
            int n = i - left + 1;
            out[i] = switch (s) {
                case MEAN -> sum / n;
                case RMS -> Math.sqrt(Math.max(0d, sumSq) / n);
                case PEAK_TO_PEAK -> v[maxq.peekFirst()] - v[minq.peekFirst()];
            };
        }
        return out;
    }

    private static double windowStat(double[] v, int from, int to, EnvelopeMode mode) {
        if (mode == EnvelopeMode.RMS) {
            double s = 0d;
            int n = to - from;
            for (int i = from; i < to; i++) {
                s += v[i] * v[i];
            }
            return Math.sqrt(s / n);
        }
        double p = 0d;
        for (int i = from; i < to; i++) {
            p = Math.max(p, Math.abs(v[i]));
        }
        return p;
    }

    static long toNanos(double size, WindowUnit unit) {
        return switch (unit) {
            case MILLISECONDS -> Math.round(size * 1_000_000d);
            case SECONDS -> Math.round(size * 1_000_000_000d);
            case SAMPLES -> throw new IllegalArgumentException("SAMPLES is not a time duration");
        };
    }

    private static void requireSeries(long[] t, double[] v) {
        if (t == null || v == null || t.length != v.length || t.length == 0) {
            throw new IllegalArgumentException("series timestamps and values required");
        }
    }

    private static void cancel(CancellationToken token, int i) {
        if (token != null && (i & 0x3FF) == 0 && token.isCancellationRequested()) {
            throw new AnalysisCancelledException();
        }
    }
}
