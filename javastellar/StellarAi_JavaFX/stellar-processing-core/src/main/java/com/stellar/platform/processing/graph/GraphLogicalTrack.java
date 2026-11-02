package com.stellar.platform.processing.graph;

import com.stellar.platform.domain.catalog.SignalCatalogKey;

import java.util.List;
import java.util.Objects;

/**
 * One selected logical signal. Every compatible measurement is a segment of this track,
 * not a separate track.
 */
public record GraphLogicalTrack(
        SignalCatalogKey logicalKey,
        List<GraphTrackSegment> segments,
        boolean sharedAbsoluteTimeline
) {
    public GraphLogicalTrack {
        Objects.requireNonNull(logicalKey, "logicalKey");
        segments = segments == null ? List.of() : List.copyOf(segments);
    }

    /** One series, one track. Used when the plot is not grouped across files. */
    public static GraphLogicalTrack single(GraphRenderSeries series) {
        return new GraphLogicalTrack(series.key(), List.of(new GraphTrackSegment(series, "", null)), false);
    }

    public String unit() {
        return segments.isEmpty() ? "" : segments.getFirst().series().unit();
    }
}
