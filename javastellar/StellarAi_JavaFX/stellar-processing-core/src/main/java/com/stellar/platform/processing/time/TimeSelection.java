package com.stellar.platform.processing.time;

/**
 * The single time filter shared by Waveforms, Analytics, Engineering and Map Route.
 *
 * <p>Immutable value so it can cross threads without defensive copying.
 */
public record TimeSelection(Kind kind, long startNanos, long endNanos, Origin origin) {

    public enum Kind {
        /** No map-derived filter: analysis views show their normal range. */
        NONE,
        /** A single instant, used for cursor placement and point snapshots. */
        POINT,
        /** An inclusive interval applied as a time filter to linked views. */
        RANGE
    }

    /** Who produced the change; consumers ignore their own echo to break update cycles. */
    public enum Origin {
        MAP,
        MAP_SEGMENT,
        WAVEFORM,
        WAVEFORM_RANGE,
        TIMELINE,
        EVENT,
        PROGRAMMATIC;

        public boolean fromMap() {
            return this == MAP || this == MAP_SEGMENT;
        }

        public boolean fromWaveform() {
            return this == WAVEFORM || this == WAVEFORM_RANGE;
        }
    }

    private static final TimeSelection NONE =
            new TimeSelection(Kind.NONE, 0, 0, Origin.PROGRAMMATIC);

    public TimeSelection {
        if (kind == Kind.RANGE && endNanos < startNanos) {
            long swap = startNanos;
            startNanos = endNanos;
            endNanos = swap;
        }
    }

    public static TimeSelection none() {
        return NONE;
    }

    public static TimeSelection none(Origin origin) {
        return new TimeSelection(Kind.NONE, 0, 0, origin);
    }

    public static TimeSelection point(long timestampNanos, Origin origin) {
        return new TimeSelection(Kind.POINT, timestampNanos, timestampNanos, origin);
    }

    public static TimeSelection range(long startNanos, long endNanos, Origin origin) {
        return new TimeSelection(Kind.RANGE, startNanos, endNanos, origin);
    }

    public boolean active() {
        return kind != Kind.NONE;
    }

    public long durationNanos() {
        return kind == Kind.RANGE ? endNanos - startNanos : 0L;
    }

    /** Equality that ignores {@link Origin}, so an echo is not treated as a new selection. */
    public boolean sameSelection(TimeSelection other) {
        return other != null
                && kind == other.kind
                && startNanos == other.startNanos
                && endNanos == other.endNanos;
    }
}
