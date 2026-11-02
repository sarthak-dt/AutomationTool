package com.stellar.platform.desktop.ui.graph;

/**
 * Which logical tracks to query and paint for the current vertical scroll position.
 * Selection stays complete. Only this window, plus a short overscan, is resident.
 */
public final class TrackQueryWindow {

    /** Tracks kept ready above and below the scroll viewport. */
    public static final int OVERSCAN = 2;

    /**
     * Overlay draws every selected signal on one canvas, so it cannot virtualize vertically.
     * Past this count the status names the cap. Stacked view scrolls the rest.
     */
    public static final int OVERLAY_CAP = 24;

    public record Range(int fromInclusive, int toExclusive) {
        public Range {
            if (fromInclusive < 0 || toExclusive < fromInclusive) {
                throw new IllegalArgumentException("track window " + fromInclusive + ".." + toExclusive);
            }
        }

        public int size() {
            return toExclusive - fromInclusive;
        }
    }

    private TrackQueryWindow() {
    }

    /**
     * @param trackCount logical tracks that occupy scroll height, hidden tracks excluded
     * @param scrollOffset pixels from the top of the stack to the top of the viewport
     * @param viewportHeight visible height of the scroll port; a non-positive value uses four tracks
     * @param trackStride nominal height of one track
     */
    public static Range of(
            int trackCount,
            double scrollOffset,
            double viewportHeight,
            double trackStride,
            int overscan
    ) {
        if (trackCount <= 0) {
            return new Range(0, 0);
        }
        double stride = Double.isFinite(trackStride) && trackStride > 1d ? trackStride : 140d;
        double view = Double.isFinite(viewportHeight) && viewportHeight > 1d ? viewportHeight : stride * 4d;
        double offset = Double.isFinite(scrollOffset) ? Math.max(0d, scrollOffset) : 0d;
        int first = (int) Math.floor(offset / stride);
        int last = (int) Math.ceil((offset + view) / stride);
        int extra = Math.max(0, overscan);
        int from = Math.max(0, first - extra);
        int to = Math.min(trackCount, Math.max(first + 1, last) + extra);
        if (to <= from) {
            to = Math.min(trackCount, from + 1);
        }
        return new Range(from, to);
    }
}
