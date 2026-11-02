package com.stellar.platform.desktop.presentation;

/**
 * Shared X/time viewport invariants for Graph analysis.
 *
 * <p>Full selected-visible analysis range and the current viewport are distinct:
 * rendering consumes the viewport; it must never derive a new shared range from one
 * signal while drawing that signal.</p>
 */
public final class GraphSharedViewport {

    /** Sub-millisecond windows against multi-second series are treated as unusable. */
    public static final long MIN_USABLE_SPAN_NANOS = 1_000_000L;
    /** When full data is multi-second, require at least this span before trusting the viewport. */
    public static final long MULTI_SECOND_DATA_NANOS = 1_000_000_000L;
    /** Display-only pad around a true single-timestamp signal (±0.5 s). */
    public static final long SINGLE_TIMESTAMP_DISPLAY_PAD_NANOS = 500_000_000L;

    private GraphSharedViewport() {}

    /** Structural validity: finite longs with a positive open interval. */
    public static boolean isStructurallyValid(long start, long end) {
        return end > start;
    }

    /**
     * Usable for multi-sample graph queries against the selected-visible full range.
     * Rejects inverted/empty ranges, ranges that miss all data, and near-zero windows
     * on long recordings (the degenerate {@code T..T} / {@code T..T+1ns} case).
     */
    public static boolean isUsable(long start, long end, long fullStart, long fullEnd) {
        if (!isStructurallyValid(start, end)) {
            return false;
        }
        boolean hasFull = fullEnd > fullStart
                || (fullStart != Long.MIN_VALUE && fullEnd != Long.MAX_VALUE && fullEnd >= fullStart);
        if (!hasFull || fullStart == Long.MIN_VALUE || fullEnd == Long.MAX_VALUE) {
            return end - start >= 1L;
        }
        if (end < fullStart || start > fullEnd) {
            return false;
        }
        long dataSpan = Math.max(0L, fullEnd - fullStart);
        long span = end - start;
        if (dataSpan == 0L) {
            // True single-timestamp full range: any positive display span that covers the point is OK.
            return span >= 1L && start <= fullStart && end >= fullEnd;
        }
        if (dataSpan > MULTI_SECOND_DATA_NANOS && span < MIN_USABLE_SPAN_NANOS) {
            return false;
        }
        return true;
    }

    /**
     * Display/query span for a legitimate single-timestamp signal. Does not invent samples;
     * only widens the visible window so the point can be plotted.
     */
    public static long[] singleTimestampDisplayRange(long timestampNanos) {
        long pad = SINGLE_TIMESTAMP_DISPLAY_PAD_NANOS;
        long start = timestampNanos - pad;
        long end = timestampNanos + pad;
        if (start > timestampNanos) {
            start = timestampNanos;
        }
        if (end < timestampNanos) {
            end = timestampNanos + 1L;
        }
        if (end <= start) {
            end = start + 1L;
        }
        return new long[] {start, end};
    }

    public static String formatNanosRange(long start, long end) {
        return start + ".." + end;
    }

    /**
     * Changes only the visible time window. {@code factor} below 1 zooms in.
     * The anchor timestamp stays at the same fraction of the window when the data range allows it.
     */
    public static long[] zoomAround(
            long start, long end, double factor, long anchor, long dataStart, long dataEnd) {
        if (!isStructurallyValid(start, end) || !(factor > 0.0) || !Double.isFinite(factor)) {
            return new long[] {start, end};
        }
        long span = end - start;
        boolean known = knownRange(dataStart, dataEnd);
        long fullSpan = known && dataEnd > dataStart ? dataEnd - dataStart : span;
        long floor = fullSpan > MULTI_SECOND_DATA_NANOS ? MIN_USABLE_SPAN_NANOS : 1L;
        if (known && fullSpan < floor) {
            floor = 1L;
        }
        long target = span;
        if (factor < 1.0) {
            target = (long) Math.floor(span * factor);
            if (target >= span) {
                target = span > floor ? span - 1L : span;
            }
            if (target < floor) {
                target = floor;
            }
        } else if (factor > 1.0) {
            target = (long) Math.ceil(span * factor);
            if (target <= span) {
                target = span + 1L;
            }
            if (known && dataEnd > dataStart) {
                target = Math.min(fullSpan, target);
            }
            if (target < span) {
                target = span;
            }
        }
        if (target < 1L) {
            target = 1L;
        }
        double ratio = 0.5;
        if (anchor >= start && anchor <= end && span > 0L) {
            ratio = Math.max(0.0, Math.min(1.0, (anchor - start) / (double) span));
        }
        long newStart = anchor - Math.round(target * ratio);
        return placeWindow(newStart, newStart + target, dataStart, dataEnd);
    }

    /** Shifts the window by {@code shiftNanos} and keeps it inside a known data range. */
    public static long[] panBy(long start, long end, long shiftNanos, long dataStart, long dataEnd) {
        if (!isStructurallyValid(start, end)) {
            return new long[] {start, end};
        }
        return placeWindow(start + shiftNanos, end + shiftNanos, dataStart, dataEnd);
    }

    /**
     * Pulls a requested window into the known data range. Does not change sample timestamps.
     * An unknown range ({@link Long#MIN_VALUE}..{@link Long#MAX_VALUE}) is left untouched.
     */
    public static long[] placeWindow(long start, long end, long dataStart, long dataEnd) {
        long a = start;
        long b = end;
        if (b <= a) {
            b = a + 1L;
        }
        if (knownRange(dataStart, dataEnd) && dataEnd == dataStart) {
            return singleTimestampDisplayRange(dataStart);
        }
        if (knownRange(dataStart, dataEnd) && dataEnd > dataStart) {
            long span = Math.min(dataEnd - dataStart, b - a);
            if (span < 1L) {
                span = 1L;
            }
            if (a < dataStart) {
                a = dataStart;
                b = a + span;
            }
            if (b > dataEnd) {
                b = dataEnd;
                a = b - span;
            }
            if (a < dataStart) {
                a = dataStart;
            }
        }
        if (b <= a) {
            b = a + 1L;
        }
        return new long[] {a, b};
    }

    private static boolean knownRange(long dataStart, long dataEnd) {
        return dataStart != Long.MIN_VALUE && dataEnd != Long.MAX_VALUE && dataEnd >= dataStart;
    }
}
