package com.stellar.platform.analysis.model;

/**
 * Inclusive timestamp window in nanos. {@link Long#MIN_VALUE}/{@link Long#MAX_VALUE} mean unbounded.
 */
public record TimeWindow(long startNanos, long endNanos) {

    public static TimeWindow unbounded() {
        return new TimeWindow(Long.MIN_VALUE, Long.MAX_VALUE);
    }

    public static TimeWindow of(long startNanos, long endNanos) {
        if (endNanos < startNanos) {
            return new TimeWindow(endNanos, startNanos);
        }
        return new TimeWindow(startNanos, endNanos);
    }

    public boolean contains(long timestampNanos) {
        return timestampNanos >= startNanos && timestampNanos <= endNanos;
    }

    public boolean isUnbounded() {
        return startNanos == Long.MIN_VALUE && endNanos == Long.MAX_VALUE;
    }

    public long durationNanos() {
        if (isUnbounded() || endNanos < startNanos) {
            return 0L;
        }
        return endNanos - startNanos;
    }

    public double durationSeconds() {
        return durationNanos() / 1_000_000_000.0d;
    }
}
