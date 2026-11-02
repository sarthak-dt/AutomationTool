package com.stellar.platform.desktop.ui.graph;

import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.processing.graph.GraphBucket;

import java.util.List;
import java.util.Locale;

/**
 * Physical Y-axis for the samples the store already holds.
 * Padding only moves the frame. It never replaces, clamps, or invents a measurement.
 */
public final class GraphAxisScale {

    private GraphAxisScale() {
    }

    public record Domain(
            double dataMin,
            double dataMax,
            double axisMin,
            double axisMax,
            boolean constant,
            boolean binary,
            boolean hasUsable
    ) {
        public static Domain empty() {
            return new Domain(Double.NaN, Double.NaN, Double.NaN, Double.NaN, false, false, false);
        }
    }

    public static Domain fromBuckets(List<GraphBucket> buckets) {
        if (buckets == null || buckets.isEmpty()) {
            return Domain.empty();
        }
        return fromCorners(buckets);
    }

    private static Domain fromCorners(List<GraphBucket> buckets) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        boolean sawZero = false;
        boolean sawOne = false;
        boolean onlyBinary = true;
        int usable = 0;
        for (GraphBucket bucket : buckets) {
            if (bucket == null) {
                continue;
            }
            double[] values = {bucket.minValue(), bucket.maxValue(), bucket.firstValue(), bucket.lastValue()};
            SignalValidity[] states = {
                    bucket.minValidity(), bucket.maxValidity(), bucket.firstValidity(), bucket.lastValidity()
            };
            for (int i = 0; i < values.length; i++) {
                if (states[i] == null || !states[i].usableWith(values[i])) {
                    continue;
                }
                double value = values[i];
                usable++;
                min = Math.min(min, value);
                max = Math.max(max, value);
                boolean zero = Math.abs(value) <= 1e-9;
                boolean one = Math.abs(value - 1.0) <= 1e-9;
                if (zero) {
                    sawZero = true;
                }
                if (one) {
                    sawOne = true;
                }
                if (!zero && !one) {
                    onlyBinary = false;
                }
            }
        }
        if (usable == 0 || !Double.isFinite(min) || !Double.isFinite(max)) {
            return Domain.empty();
        }
        boolean binary = onlyBinary && sawZero && sawOne;
        return frame(min, max, binary);
    }

    /** Shared-unit overlay: the frame is the union of usable extrema already collected. */
    public static Domain fromRange(double dataMin, double dataMax) {
        if (!Double.isFinite(dataMin) || !Double.isFinite(dataMax)) {
            return Domain.empty();
        }
        double min = Math.min(dataMin, dataMax);
        double max = Math.max(dataMin, dataMax);
        return frame(min, max, false);
    }

    private static Domain frame(double dataMin, double dataMax, boolean binary) {
        boolean constant = Math.abs(dataMax - dataMin) <= Math.max(1e-12, Math.abs(dataMax) * 1e-12);
        double axisMin;
        double axisMax;
        if (binary) {
            axisMin = 0.0;
            axisMax = 1.0;
        } else {
            double span = dataMax - dataMin;
            double pad;
            if (!Double.isFinite(span)) {
                pad = 0.0;
            } else if (constant) {
                pad = Math.max(0.5, Math.abs(dataMin) * 0.05);
            } else {
                pad = span * 0.06;
            }
            axisMin = dataMin - pad;
            axisMax = dataMax + pad;
            if (!Double.isFinite(axisMin)) {
                axisMin = dataMin;
            }
            if (!Double.isFinite(axisMax)) {
                axisMax = dataMax;
            }
            if (!(axisMax > axisMin)) {
                double nudge = Math.ulp(dataMax);
                if (!Double.isFinite(nudge) || nudge == 0.0) {
                    nudge = Math.max(Math.ulp(1.0), Math.abs(dataMax) * 1e-6);
                }
                axisMin = dataMin;
                axisMax = dataMax + nudge;
                if (!Double.isFinite(axisMax) || !(axisMax > axisMin)) {
                    axisMin = dataMin;
                    axisMax = dataMax;
                }
            }
        }
        return new Domain(dataMin, dataMax, axisMin, axisMax, constant, binary, true);
    }

    /** Tick text for a physical value. Non-finite numbers are not printed as measurements. */
    public static String formatTick(double value) {
        if (!Double.isFinite(value)) {
            return "—";
        }
        if (value == 0.0) {
            return "0";
        }
        double magnitude = Math.abs(value);
        if (magnitude >= 1_000_000d || magnitude < 0.0001d) {
            return Double.toString(value);
        }
        String compact = String.format(Locale.ROOT, "%.6f", value);
        int dot = compact.indexOf('.');
        if (dot < 0) {
            return compact;
        }
        int end = compact.length();
        while (end > dot + 1 && compact.charAt(end - 1) == '0') {
            end--;
        }
        if (end > 0 && compact.charAt(end - 1) == '.') {
            end--;
        }
        return compact.substring(0, end);
    }

    /** Normalized mode only. The number is a percent of that signal's own range, not an engineering unit. */
    public static String formatNormalizedPercent(double percent) {
        if (!Double.isFinite(percent)) {
            return "—";
        }
        return String.format(Locale.ROOT, "%.4g%%", percent);
    }
}
