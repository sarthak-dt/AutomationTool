package com.stellar.platform.analysis.rules;

/**
 * Timestamp-based duration, optional hysteresis exit, optional debounce.
 * Duration is never sample-count based.
 */
public record TemporalConstraint(
        DurationOp durationOp,
        long durationNanos,
        Double hysteresisExit,
        long debounceNanos,
        long mergeGapNanos
) {
    public static TemporalConstraint none() {
        return new TemporalConstraint(DurationOp.NONE, 0L, null, 0L, 0L);
    }

    public static TemporalConstraint atLeast(long nanos) {
        return new TemporalConstraint(DurationOp.FOR_AT_LEAST, nanos, null, 0L, 0L);
    }

    public TemporalConstraint {
        durationOp = durationOp == null ? DurationOp.NONE : durationOp;
        if (durationNanos < 0L || debounceNanos < 0L || mergeGapNanos < 0L) {
            throw new IllegalArgumentException("temporal nanos must be >= 0");
        }
    }

    public boolean hasDuration() {
        return durationOp != DurationOp.NONE && durationNanos > 0L;
    }
}
