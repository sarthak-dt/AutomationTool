package com.stellar.platform.processing.graph;

import java.util.Objects;

/**
 * One measurement file's samples inside a logical waveform track.
 * Timestamps on {@link #series()} are the stored measurement timestamps.
 * {@link #absoluteOriginNanos()} is a display offset from a comparable recording clock, never a rewritten sample.
 */
public record GraphTrackSegment(
        GraphRenderSeries series,
        String measurementLabel,
        Long absoluteOriginNanos,
        Long layoutStartNanos,
        Long layoutEndNanos
) {
    public GraphTrackSegment(GraphRenderSeries series, String measurementLabel, Long absoluteOriginNanos) {
        this(series, measurementLabel, absoluteOriginNanos, null, null);
    }

    public GraphTrackSegment {
        Objects.requireNonNull(series, "series");
        measurementLabel = measurementLabel == null ? "" : measurementLabel;
        if (layoutStartNanos != null && layoutEndNanos != null && layoutEndNanos < layoutStartNanos) {
            throw new IllegalArgumentException("layout end before start");
        }
    }

    /** Unknown-order overview slot. Null bounds mean this segment is not on that axis. */
    public boolean layoutSlot() {
        return layoutStartNanos != null && layoutEndNanos != null && layoutEndNanos > layoutStartNanos;
    }
}
