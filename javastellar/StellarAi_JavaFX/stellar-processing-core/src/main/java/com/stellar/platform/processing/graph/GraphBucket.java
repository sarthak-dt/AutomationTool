package com.stellar.platform.processing.graph;

import com.stellar.platform.domain.SignalValidity;

/**
 * Loss-aware-free visual summary of a time bucket. Extrema keep short spikes visible
 * while first/last preserve entry/exit shape. Raw samples remain owned by the source store.
 * Min and max describe usable finite measurements. An unusable sample keeps its own value
 * on first or last so it can be traced, and does not define the range.
 */
public record GraphBucket(
        long firstTimestampNanos, double firstValue,
        long minTimestampNanos, double minValue,
        long maxTimestampNanos, double maxValue,
        long lastTimestampNanos, double lastValue,
        long sampleCount,
        SignalValidity firstValidity,
        SignalValidity minValidity,
        SignalValidity maxValidity,
        SignalValidity lastValidity,
        String firstLabel,
        String minLabel,
        String maxLabel,
        String lastLabel
) {
    public GraphBucket(
            long firstTimestampNanos, double firstValue,
            long minTimestampNanos, double minValue,
            long maxTimestampNanos, double maxValue,
            long lastTimestampNanos, double lastValue,
            long sampleCount
    ) {
        this(firstTimestampNanos, firstValue, minTimestampNanos, minValue, maxTimestampNanos, maxValue,
                lastTimestampNanos, lastValue, sampleCount, null, null, null, null);
    }

    public GraphBucket(
            long firstTimestampNanos, double firstValue,
            long minTimestampNanos, double minValue,
            long maxTimestampNanos, double maxValue,
            long lastTimestampNanos, double lastValue,
            long sampleCount,
            SignalValidity firstValidity,
            SignalValidity minValidity,
            SignalValidity maxValidity,
            SignalValidity lastValidity
    ) {
        this(firstTimestampNanos, firstValue, minTimestampNanos, minValue, maxTimestampNanos, maxValue,
                lastTimestampNanos, lastValue, sampleCount, firstValidity, minValidity, maxValidity, lastValidity,
                null, null, null, null);
    }

    public GraphBucket {
        if (sampleCount < 1) throw new IllegalArgumentException("sampleCount must be >= 1");
        firstValidity = firstValidity == null ? implied(firstValue) : firstValidity;
        minValidity = minValidity == null ? implied(minValue) : minValidity;
        maxValidity = maxValidity == null ? implied(maxValue) : maxValidity;
        lastValidity = lastValidity == null ? implied(lastValue) : lastValidity;
    }

    public boolean minUsable() {
        return minValidity.usableWith(minValue);
    }

    public boolean maxUsable() {
        return maxValidity.usableWith(maxValue);
    }

    public boolean lastUsable() {
        return lastValidity.usableWith(lastValue);
    }

    public boolean firstUsable() {
        return firstValidity.usableWith(firstValue);
    }

    /** Label for a categorical corner, when this bucket carries one. */
    public String categoricalLabel() {
        if (present(firstLabel)) {
            return firstLabel;
        }
        if (present(lastLabel)) {
            return lastLabel;
        }
        if (present(minLabel)) {
            return minLabel;
        }
        return present(maxLabel) ? maxLabel : null;
    }

    private static boolean present(String label) {
        return label != null && !label.isBlank();
    }

    private static SignalValidity implied(double value) {
        return Double.isFinite(value) ? SignalValidity.VALID : SignalValidity.NON_FINITE_VALUE;
    }
}
