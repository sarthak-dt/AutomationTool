package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;

import java.util.List;

/**
 * Inclusive time window for reading a measurement's GPS samples.
 *
 * <p>The bounds come from that measurement's catalog timestamps. They are not the waveform
 * viewport, the selected-signal span, or a decimated render ladder. Map Route and the
 * engineering report both use this window, so "GPS samples" counts the same points.
 */
public final class GpsCatalogWindow {

    private GpsCatalogWindow() {
    }

    /**
     * @return {@code [startNanos, endNanos]}, both inclusive. An empty catalog falls back to
     *         a range that does not invent samples: the query still returns only stored points.
     */
    public static long[] inclusive(List<SignalCatalogEntry> entries) {
        long start = Long.MAX_VALUE;
        long end = Long.MIN_VALUE;
        if (entries != null) {
            for (SignalCatalogEntry entry : entries) {
                if (entry == null) {
                    continue;
                }
                if (entry.firstTimestampNanos() != null) {
                    start = Math.min(start, entry.firstTimestampNanos());
                }
                if (entry.lastTimestampNanos() != null) {
                    end = Math.max(end, entry.lastTimestampNanos());
                }
            }
        }
        if (start == Long.MAX_VALUE || end == Long.MIN_VALUE || end < start) {
            return new long[]{0L, Long.MAX_VALUE / 4};
        }
        return new long[]{start, end};
    }
}
