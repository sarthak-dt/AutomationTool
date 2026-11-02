package com.stellar.platform.processing.graph;

import com.stellar.platform.domain.catalog.SignalCatalogKey;
import java.util.List;

/** Immutable viewport contract; renderer size, not file size, drives visual detail. */
public record GraphViewportRequest(
        List<SignalCatalogKey> signalKeys,
        long startTimestampNanos,
        long endTimestampNanos,
        int pixelWidth,
        long timeOriginNanos
) {
    public GraphViewportRequest(List<SignalCatalogKey> signalKeys, long startTimestampNanos, long endTimestampNanos, int pixelWidth) {
        this(signalKeys, startTimestampNanos, endTimestampNanos, pixelWidth, 0L);
    }

    public GraphViewportRequest {
        signalKeys = signalKeys == null ? List.of() : List.copyOf(signalKeys);
        if (endTimestampNanos < startTimestampNanos) throw new IllegalArgumentException("end before start");
        if (pixelWidth < 1) throw new IllegalArgumentException("pixelWidth must be >= 1");
    }
}
