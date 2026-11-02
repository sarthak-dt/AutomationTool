package com.stellar.platform.processing.graph;

import java.util.ArrayList;
import java.util.List;

/**
 * Places measurement segments inside one logical track.
 *
 * <p>Relative or unknown clocks stay in separate labeled panes, in the supplied order.
 * That order is identity, not a claim of chronology, and panes do not share an X axis,
 * so overlapping relative timestamps are not drawn on top of each other and are not connected.
 *
 * <p>Comparable clocks share one X axis. A segment's display time is its stored timestamp
 * plus the recording origin. Gaps between files stay empty. Samples are not rewritten.
 */
public final class GraphTrackArrangement {

    public enum Mode {
        SHARED_ABSOLUTE,
        INDEPENDENT_RELATIVE
    }

    public record Input(long firstTimestampNanos, long lastTimestampNanos, Long absoluteOriginNanos) {
    }

    /**
     * @param x0 left edge of the pane
     * @param x1 right edge of the pane
     * @param axisStart display-time start mapped to {@code x0}
     * @param axisEnd display-time end mapped to {@code x1}
     * @param originNanos added to a stored timestamp before mapping; zero for a relative pane
     */
    public record Pane(int index, double x0, double x1, long axisStart, long axisEnd, long originNanos) {
    }

    private GraphTrackArrangement() {
    }

    public static Mode mode(boolean sharedAbsoluteTimeline, int segmentCount) {
        return sharedAbsoluteTimeline && segmentCount > 1 ? Mode.SHARED_ABSOLUTE : Mode.INDEPENDENT_RELATIVE;
    }

    public static List<Pane> panes(Mode mode, List<Input> segments, double left, double right) {
        if (segments == null || segments.isEmpty()) {
            return List.of();
        }
        double span = Math.max(0, right - left);
        if (mode == Mode.SHARED_ABSOLUTE) {
            return shared(segments, left, left + span);
        }
        if (segments.size() == 1) {
            Input only = segments.getFirst();
            long start = only.firstTimestampNanos();
            long end = Math.max(start, only.lastTimestampNanos());
            return List.of(new Pane(0, left, left + span, start, end, 0L));
        }
        double gap = Math.min(8.0, Math.max(2.0, span * 0.004));
        if (gap * (segments.size() - 1) >= span) {
            gap = 0;
        }
        double paneWidth = (span - gap * (segments.size() - 1)) / segments.size();
        List<Pane> out = new ArrayList<>(segments.size());
        double x = left;
        for (int i = 0; i < segments.size(); i++) {
            Input segment = segments.get(i);
            long start = segment.firstTimestampNanos();
            long end = Math.max(start, segment.lastTimestampNanos());
            out.add(new Pane(i, x, x + paneWidth, start, end, 0L));
            x += paneWidth + gap;
        }
        return List.copyOf(out);
    }

    private static List<Pane> shared(List<Input> segments, double left, double right) {
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        long[] origins = new long[segments.size()];
        for (int i = 0; i < segments.size(); i++) {
            Input segment = segments.get(i);
            long origin = segment.absoluteOriginNanos() == null ? 0L : segment.absoluteOriginNanos();
            origins[i] = origin;
            min = Math.min(min, addSaturating(origin, segment.firstTimestampNanos()));
            max = Math.max(max, addSaturating(origin, segment.lastTimestampNanos()));
        }
        if (min > max) {
            min = 0L;
            max = 1L;
        }
        List<Pane> out = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            out.add(new Pane(i, left, right, min, max, origins[i]));
        }
        return List.copyOf(out);
    }

    /**
     * The pane that contains {@code x}. Shared-absolute panes occupy the same pixels,
     * so more than one match means x alone does not identify a measurement.
     */
    public static int paneContaining(List<Pane> panes, double x) {
        if (panes == null || panes.isEmpty() || !Double.isFinite(x)) {
            return -1;
        }
        int found = -1;
        for (int i = 0; i < panes.size(); i++) {
            Pane pane = panes.get(i);
            if (pane != null && x >= pane.x0() && x <= pane.x1()) {
                if (found >= 0) {
                    return -1;
                }
                found = i;
            }
        }
        return found;
    }

    public static long addSaturating(long origin, long timestamp) {
        try {
            return Math.addExact(origin, timestamp);
        } catch (ArithmeticException overflow) {
            return timestamp < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }
}
